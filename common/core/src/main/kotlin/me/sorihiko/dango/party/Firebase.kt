package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Firebase 的最小客户端（不用官方 SDK，直接走 REST）：
 *   - 匿名登录（每台电脑一个固定身份，刷新令牌存在 config/dangomusic/firebase_auth.json）
 *   - 实时数据库的读 / 写 / 删
 *   - 实时监听（Server-Sent Events 长连接，有变化立刻推过来）
 *   - 所有请求按一起听歌的连接方式（[PartyProxy]）走；配了中转时先走中转（[FirebaseEndpoints]）
 *   - 服务器时间（各人电脑时钟不一样，同步进度要用同一个时间）
 * 是 [PartyTransport] 的第一个实现。
 */
object Firebase : PartyTransport {

    override val configured: Boolean
        get() = FirebaseConfig.API_KEY.isNotBlank() && FirebaseConfig.DATABASE_URL.startsWith("https://")

    @Volatile override var uid: String = ""
        private set
    @Volatile private var idToken: String = ""
    @Volatile private var refreshToken: String = ""
    @Volatile private var tokenExpiresAt = 0L
    /** 服务器时间 - 本机时间 */
    @Volatile private var clockOffset = 0L

    private val authFile get() = DangoMusic.instance.clientDir.resolve("firebase_auth.json").toFile()
    private val endpoints: FirebaseEndpoints by lazy {
        FirebaseEndpoints(FirebaseConfig.RELAY_URL, FirebaseConfig.DATABASE_URL.trimEnd('/'))
    }

    override val route: PartyTransport.Route get() = endpoints.route

    override fun serverNow(): Long = System.currentTimeMillis() + clockOffset

    override fun serverTimestamp(): JsonElement = JsonParser().parse("""{".sv":"timestamp"}""")

    // ---------------------------------------------------------------- 登录

    /** 确保已登录且令牌没过期；返回 false 表示连不上 Firebase */
    @Synchronized
    override fun ensureAuth(): Boolean {
        if (!configured) return false
        if (idToken.isNotEmpty() && System.currentTimeMillis() < tokenExpiresAt - 5 * 60_000) return true
        if (refreshToken.isEmpty()) loadSaved()
        if (refreshToken.isNotEmpty()) {
            if (refresh()) return true
            // 网络不通时别去重新注册：注册一样连不上，白白多等一轮；网络恰好恢复时还会换掉这台电脑的身份
            if (lastStatus == -1) return false
        }
        return signUp()
    }

    private fun loadSaved() {
        try {
            val f = authFile
            if (!f.isFile) return
            val o = JsonParser().parse(f.readText(StandardCharsets.UTF_8)).asJsonObject
            refreshToken = o.get("refreshToken")?.asString ?: ""
            uid = o.get("uid")?.asString ?: ""
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            val o = JsonObject()
            o.addProperty("uid", uid)
            o.addProperty("refreshToken", refreshToken)
            authFile.parentFile.mkdirs()
            authFile.writeText(o.toString(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun signUp(): Boolean {
        val res = request(
            "POST", FirebaseEndpoints.Target.SIGN_UP, "", "key=" + FirebaseConfig.API_KEY,
            """{"returnSecureToken":true}""",
        )?.json ?: return false
        val o = res.asJsonObject
        uid = o.get("localId")?.asString ?: return false
        idToken = o.get("idToken")?.asString ?: return false
        refreshToken = o.get("refreshToken")?.asString ?: ""
        tokenExpiresAt = System.currentTimeMillis() + (o.get("expiresIn")?.asString?.toLongOrNull() ?: 3600L) * 1000
        save()
        return true
    }

    private fun refresh(): Boolean {
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
        val res = request(
            "POST", FirebaseEndpoints.Target.REFRESH, "", "key=" + FirebaseConfig.API_KEY,
            body,
            form = true,
        )?.json ?: return false
        val o = res.asJsonObject
        idToken = o.get("id_token")?.asString ?: return false
        refreshToken = o.get("refresh_token")?.asString ?: refreshToken
        uid = o.get("user_id")?.asString ?: uid
        tokenExpiresAt = System.currentTimeMillis() + (o.get("expires_in")?.asString?.toLongOrNull() ?: 3600L) * 1000
        save()
        return true
    }

    /** 写一个服务器时间戳再读回来，算出本机和服务器的时差 */
    override fun syncClock() {
        if (!ensureAuth()) return
        val before = System.currentTimeMillis()
        val res = put("clock/$uid", serverTimestamp())
        val after = System.currentTimeMillis()
        val server = try { res?.asLong } catch (_: Exception) { null } ?: return
        clockOffset = server - (before + after) / 2
    }

    // ---------------------------------------------------------------- 数据库

    private fun authQuery() = "auth=$idToken"

    private fun db(method: String, path: String, body: String?, headers: Map<String, String> = emptyMap()) =
        request(method, FirebaseEndpoints.Target.DB, path, authQuery(), body, headers = headers)

    override fun get(path: String): JsonElement? {
        if (!ensureAuth()) return null
        return db("GET", path, null)?.json
    }

    /** 版本号用 Firebase 的 ETag */
    override fun getWithEtag(path: String): PartyTransport.Snapshot? {
        if (!ensureAuth()) return null
        val res = db("GET", path, null, mapOf("X-Firebase-ETag" to "true")) ?: return null
        return PartyTransport.Snapshot(res.json.takeUnless { it.isJsonNull }, res.etag ?: return null)
    }

    /**
     * 只有路径上的数据还是 [etag] 那个版本时才写入（路径不存在时 etag 是 "null_etag"）。
     * 期间被别人改过会失败，[lastStatus] 是 412。
     */
    override fun putIfUnchanged(path: String, value: JsonElement, etag: String): JsonElement? {
        if (!ensureAuth()) return null
        return db("PUT", path, value.toString(), mapOf("if-match" to etag))?.json
    }

    override fun put(path: String, value: JsonElement): JsonElement? {
        if (!ensureAuth()) return null
        return db("PUT", path, value.toString())?.json
    }

    override fun patch(path: String, value: JsonElement): JsonElement? {
        if (!ensureAuth()) return null
        return db("PATCH", path, value.toString())?.json
    }

    override fun delete(path: String): Boolean {
        if (!ensureAuth()) return false
        return db("DELETE", path, null) != null
    }

    @Volatile override var lastStatus = 0
        private set
    @Volatile override var lastFailure = PartyTransport.Failure.NONE
        private set

    // ---------------------------------------------------------------- 流量统计（日志里对比瘦身前后用）

    private val statRequests = AtomicInteger()
    private val statPushes = AtomicInteger()
    private val statBytesOut = AtomicLong()
    private val statBytesIn = AtomicLong()
    private val statSince = AtomicLong(System.currentTimeMillis())

    /** 有流量时大约每分钟写一行日志：请求数（含登录、长连接）、收到的推送数、收发字节 */
    private fun maybeReport() {
        val since = statSince.get()
        val now = System.currentTimeMillis()
        if (now - since < 60_000 || !statSince.compareAndSet(since, now)) return
        DangoLogger.info(
            "Listen Together traffic in {}s: {} requests, {} pushes, {} B sent, {} B received",
            (now - since) / 1000, statRequests.getAndSet(0), statPushes.getAndSet(0),
            statBytesOut.getAndSet(0), statBytesIn.getAndSet(0),
        )
    }

    private class Response(val json: JsonElement, val etag: String?)

    private fun request(
        method: String,
        target: FirebaseEndpoints.Target,
        path: String,
        query: String,
        body: String?,
        form: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        statRequests.incrementAndGet()
        maybeReport()
        try {
            val bytes = body?.toByteArray(StandardCharsets.UTF_8)
            if (bytes != null) statBytesOut.addAndGet(bytes.size.toLong())
            val contentType = if (form) "application/x-www-form-urlencoded" else "application/json; charset=utf-8"
            val res = endpoints.send(target, path, query, method, bytes, contentType, headers)
            lastStatus = res.code
            if (res.code !in 200..299) {
                if (res.code == 401) tokenExpiresAt = 0L
                lastFailure = PartyTransport.Failure.REJECTED
                return null
            }
            lastFailure = PartyTransport.Failure.NONE
            val raw = res.body ?: ByteArray(0)
            statBytesIn.addAndGet(raw.size.toLong())
            val text = String(raw, StandardCharsets.UTF_8)
            return Response(JsonParser().parse(text.ifBlank { "null" }), res.etag)
        } catch (e: Exception) {
            DangoLogger.warn("Firebase {} failed: {}", method, e.toString())
            lastStatus = -1
            lastFailure = when {
                e is PartyProxy.ProxyUnavailable -> PartyTransport.Failure.PROXY_UNREACHABLE
                endpoints.hasRelay -> PartyTransport.Failure.RELAY_UNREACHABLE
                target != FirebaseEndpoints.Target.DB -> PartyTransport.Failure.AUTH_UNREACHABLE
                else -> PartyTransport.Failure.SERVER_UNREACHABLE
            }
            return null
        }
    }

    // ---------------------------------------------------------------- 实时监听

    override fun listen(path: String, onEvent: (PartyEvent) -> Unit): () -> Unit {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val conn = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val t = Thread({
            while (running.get()) {
                try {
                    if (!ensureAuth()) {
                        Thread.sleep(3000)
                        continue
                    }
                    statRequests.incrementAndGet()
                    val c = endpoints.openStream(path, authQuery(), 70_000)   // 服务器每 30 秒发一次 keep-alive
                    conn.set(c)
                    if (!running.get()) {
                        c.disconnect()
                        break
                    }
                    if (c.responseCode !in 200..299) {
                        if (c.responseCode == 401) tokenExpiresAt = 0L
                        c.disconnect()
                        Thread.sleep(3000)
                        continue
                    }
                    BufferedReader(InputStreamReader(c.inputStream, StandardCharsets.UTF_8)).use { r ->
                        // 令牌快过期时主动断开重连（换新令牌）
                        val keepGoing = { running.get() && System.currentTimeMillis() <= tokenExpiresAt - 5 * 60_000 }
                        PartyHttp.readSse(r, keepGoing) { event, data ->
                            when (event) {
                                "put", "patch" -> {
                                    statPushes.incrementAndGet()
                                    statBytesIn.addAndGet(data.length.toLong() + 5)
                                    maybeReport()
                                    // 连上时的第一条就是整个路径的数据，之后是增量
                                    PartyEvent.fromSse(event, data)?.let(onEvent)
                                    true
                                }
                                "auth_revoked", "cancel" -> {
                                    tokenExpiresAt = 0L
                                    false
                                }
                                else -> true
                            }
                        }
                    }
                    c.disconnect()
                } catch (e: Exception) {
                    if (running.get()) {
                        DangoLogger.warn("Firebase listen dropped: {}", e.toString())
                        try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                    }
                }
            }
        }, "Dango-FirebaseListen")
        t.isDaemon = true
        t.start()
        return {
            running.set(false)
            try { conn.get()?.disconnect() } catch (_: Exception) {}
        }
    }
}
