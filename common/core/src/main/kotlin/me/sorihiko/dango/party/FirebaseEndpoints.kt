package me.sorihiko.dango.party

import me.sorihiko.dango.DangoLogger

import java.io.IOException
import java.net.HttpURLConnection

/**
 * Firebase 的三个地址（Google 匿名登录、刷新令牌、实时数据库），以及可选的中转（relay/worker.js）。
 *
 * 配了中转时先走中转。下面两种情况改走直连 Firebase，并在 [RELAY_DOWN_MS] 内先直连：
 *   - 连中转时在建立连接阶段就失败（[PartyHttp.NotSent]），请求肯定没发出去：任何请求都可以改走直连重发。
 *   - 响应里没有 [PartyHttp.RELAY_HEADER]，说明 Worker 根本没运行（比如当天免费额度用完，Cloudflare 自己回的错误页）。
 *     读请求和登录 / 刷新令牌改走直连重发；数据库写请求不重发（拿不准有没有写进去），只是之后先直连。
 * 读请求（GET）本身可以重发，经中转出了任何网络错误都改走直连；其他请求经过中转以后再出的错一律不重试。
 */
class FirebaseEndpoints(
    /** 中转地址，空字符串表示不用中转 */
    private val relayBase: String,
    private val dbBase: String,
    private val idBase: String = "https://identitytoolkit.googleapis.com",
    private val tokenBase: String = "https://securetoken.googleapis.com",
) {

    enum class Target { SIGN_UP, REFRESH, DB }

    val hasRelay: Boolean get() = relayBase.isNotEmpty()

    @Volatile private var relayDownUntil = 0L

    /** 最近一次请求走的线路 */
    @Volatile var route: PartyTransport.Route = if (hasRelay) PartyTransport.Route.RELAY else PartyTransport.Route.DIRECT
        private set

    fun url(target: Target, path: String, query: String, viaRelay: Boolean): String {
        val base = when (target) {
            Target.SIGN_UP -> (if (viaRelay) "$relayBase/id" else idBase) + "/v1/accounts:signUp"
            Target.REFRESH -> (if (viaRelay) "$relayBase/token" else tokenBase) + "/v1/token"
            Target.DB -> (if (viaRelay) "$relayBase/db" else dbBase.trimEnd('/')) + "/" + path.trim('/') + ".json"
        }
        return if (query.isEmpty()) base else "$base?$query"
    }

    private fun relayUsable(): Boolean = hasRelay && System.currentTimeMillis() >= relayDownUntil

    private fun relayDown(reason: String) {
        if (System.currentTimeMillis() >= relayDownUntil) {
            DangoLogger.warn("Listen Together: relay unavailable ({}), using Firebase directly for now", reason)
        }
        relayDownUntil = System.currentTimeMillis() + RELAY_DOWN_MS
        route = PartyTransport.Route.RELAY_DOWN
    }

    private fun direct() {
        route = if (hasRelay) PartyTransport.Route.RELAY_DOWN else PartyTransport.Route.DIRECT
    }

    /** 发一个请求，规则见类说明 */
    @Throws(IOException::class)
    fun send(
        target: Target,
        path: String,
        query: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String> = emptyMap(),
    ): PartyHttp.Result {
        if (relayUsable()) {
            try {
                val res = PartyHttp.send(method, url(target, path, query, true), body, contentType, headers)
                if (res.relayed) {
                    route = PartyTransport.Route.RELAY
                    return res
                }
                relayDown("no relay marker, HTTP " + res.code)
                if (target == Target.DB && method != "GET") return res
            } catch (e: PartyProxy.ProxyUnavailable) {
                throw e
            } catch (e: IOException) {
                if (e !is PartyHttp.NotSent && method != "GET") throw e
                relayDown(e.toString())
            }
        }
        direct()
        return PartyHttp.send(method, url(target, path, query, false), body, contentType, headers)
    }

    /** 打开实时推送长连接（GET，可以放心改走直连），返回已经拿到状态码的连接 */
    @Throws(IOException::class)
    fun openStream(path: String, query: String, readTimeoutMs: Int): HttpURLConnection {
        if (relayUsable()) {
            try {
                val c = PartyHttp.openStream(url(Target.DB, path, query, true), readTimeoutMs)
                val code = c.responseCode
                if (c.getHeaderField(PartyHttp.RELAY_HEADER) != null) {
                    route = PartyTransport.Route.RELAY
                    return c
                }
                c.disconnect()
                relayDown("no relay marker, HTTP $code")
            } catch (e: PartyProxy.ProxyUnavailable) {
                throw e
            } catch (e: IOException) {
                relayDown(e.toString())
            }
        }
        direct()
        val c = PartyHttp.openStream(url(Target.DB, path, query, false), readTimeoutMs)
        c.responseCode
        return c
    }

    companion object {
        /** 中转不可用后，这段时间内先直连，不再每次都先试中转 */
        const val RELAY_DOWN_MS = 60_000L
    }
}
