package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Firebase 的最小客户端（不用官方 SDK，直接走 REST）：
 *   - 匿名登录（每台电脑一个固定身份，刷新令牌存在 config/dangomusic/firebase_auth.json）
 *   - 实时数据库的读 / 写 / 删
 *   - 实时监听（Server-Sent Events 长连接，有变化立刻推过来）
 *   - 服务器时间（各人电脑时钟不一样，同步进度要用同一个时间）
 */
object Firebase {

    /** 配置填好了没有 */
    val configured: Boolean
        get() = FirebaseConfig.API_KEY.isNotBlank() && FirebaseConfig.DATABASE_URL.startsWith("https://")

    @Volatile var uid: String = ""
        private set
    @Volatile private var idToken: String = ""
    @Volatile private var refreshToken: String = ""
    @Volatile private var tokenExpiresAt = 0L
    /** 服务器时间 - 本机时间 */
    @Volatile private var clockOffset = 0L

    private val authFile get() = DangoMusic.instance.clientDir.resolve("firebase_auth.json").toFile()
    private val dbBase get() = FirebaseConfig.DATABASE_URL.trimEnd('/')

    fun serverNow(): Long = System.currentTimeMillis() + clockOffset

    // ---------------------------------------------------------------- 登录

    /** 确保已登录且令牌没过期；返回 false 表示连不上 Firebase */
    @Synchronized
    fun ensureAuth(): Boolean {
        if (!configured) return false
        if (idToken.isNotEmpty() && System.currentTimeMillis() < tokenExpiresAt - 5 * 60_000) return true
        if (refreshToken.isEmpty()) loadSaved()
        if (refreshToken.isNotEmpty() && refresh()) return true
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
        val res = http(
            "POST",
            "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + FirebaseConfig.API_KEY,
            """{"returnSecureToken":true}""",
        ) ?: return false
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
        val res = http(
            "POST",
            "https://securetoken.googleapis.com/v1/token?key=" + FirebaseConfig.API_KEY,
            body,
            form = true,
        ) ?: return false
        val o = res.asJsonObject
        idToken = o.get("id_token")?.asString ?: return false
        refreshToken = o.get("refresh_token")?.asString ?: refreshToken
        uid = o.get("user_id")?.asString ?: uid
        tokenExpiresAt = System.currentTimeMillis() + (o.get("expires_in")?.asString?.toLongOrNull() ?: 3600L) * 1000
        save()
        return true
    }

    /** 写一个服务器时间戳再读回来，算出本机和服务器的时差 */
    fun syncClock() {
        if (!ensureAuth()) return
        val before = System.currentTimeMillis()
        val res = put("clock/$uid", JsonParser().parse("""{".sv":"timestamp"}"""))
        val after = System.currentTimeMillis()
        val server = try { res?.asLong } catch (_: Exception) { null } ?: return
        clockOffset = server - (before + after) / 2
    }

    // ---------------------------------------------------------------- 数据库

    private fun url(path: String) = "$dbBase/${path.trim('/')}.json?auth=$idToken"

    fun get(path: String): JsonElement? {
        if (!ensureAuth()) return null
        return http("GET", url(path), null)
    }

    /** 读到的数据 + 版本号（ETag），配合 [putIfUnchanged] 做"没被别人改过才写" */
    class Snapshot(val value: JsonElement?, val etag: String)

    fun getWithEtag(path: String): Snapshot? {
        if (!ensureAuth()) return null
        val res = request("GET", url(path), null, headers = mapOf("X-Firebase-ETag" to "true")) ?: return null
        return Snapshot(res.json.takeUnless { it.isJsonNull }, res.etag ?: return null)
    }

    /**
     * 只有路径上的数据还是 [etag] 那个版本时才写入（路径不存在时 etag 是 "null_etag"）。
     * 期间被别人改过会失败，[lastStatus] 是 412。
     */
    fun putIfUnchanged(path: String, value: JsonElement, etag: String): JsonElement? {
        if (!ensureAuth()) return null
        return request("PUT", url(path), value.toString(), headers = mapOf("if-match" to etag))?.json
    }

    fun put(path: String, value: JsonElement): JsonElement? {
        if (!ensureAuth()) return null
        return http("PUT", url(path), value.toString())
    }

    fun patch(path: String, value: JsonElement): JsonElement? {
        if (!ensureAuth()) return null
        return http("PATCH", url(path), value.toString())
    }

    fun delete(path: String): Boolean {
        if (!ensureAuth()) return false
        return http("DELETE", url(path), null) != null
    }

    /** 最近一次请求的 HTTP 状态码（界面上显示错误原因用） */
    @Volatile var lastStatus = 0
        private set

    private class Response(val json: JsonElement, val etag: String?)

    private fun http(method: String, url: String, body: String?, form: Boolean = false): JsonElement? =
        request(method, url, body, form)?.json

    private fun request(
        method: String,
        url: String,
        body: String?,
        form: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection 不支持 PATCH，用 Firebase 认的 X-HTTP-Method-Override
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty(
                    "Content-Type",
                    if (form) "application/x-www-form-urlencoded" else "application/json; charset=utf-8",
                )
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            lastStatus = code
            if (code !in 200..299) {
                if (code == 401) tokenExpiresAt = 0L
                return null
            }
            val text = conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
            return Response(JsonParser().parse(text.ifBlank { "null" }), conn.getHeaderField("ETag"))
        } catch (e: Exception) {
            DangoLogger.warn("Firebase {} failed: {}", method, e.toString())
            lastStatus = -1
            return null
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- 实时监听

    /**
     * 监听某个路径，有任何变化就调用 onChange（在后台线程）。断线会自动重连。
     * 返回一个停止函数。
     */
    fun listen(path: String, onChange: () -> Unit): () -> Unit {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val conn = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val t = Thread({
            while (running.get()) {
                try {
                    if (!ensureAuth()) {
                        Thread.sleep(3000)
                        continue
                    }
                    val c = URL(url(path)).openConnection() as HttpURLConnection
                    conn.set(c)
                    c.setRequestProperty("Accept", "text/event-stream")
                    c.connectTimeout = 8000
                    c.readTimeout = 70_000          // 服务器每 30 秒发一次 keep-alive
                    c.instanceFollowRedirects = true
                    if (c.responseCode !in 200..299) {
                        if (c.responseCode == 401) tokenExpiresAt = 0L
                        c.disconnect()
                        Thread.sleep(3000)
                        continue
                    }
                    BufferedReader(InputStreamReader(c.inputStream, StandardCharsets.UTF_8)).use { r ->
                        var event = ""
                        while (running.get()) {
                            val line = r.readLine() ?: break
                            when {
                                line.startsWith("event:") -> event = line.substring(6).trim()
                                line.startsWith("data:") -> {
                                    when (event) {
                                        "put", "patch" -> onChange()
                                        "auth_revoked", "cancel" -> {
                                            tokenExpiresAt = 0L
                                            return@use
                                        }
                                    }
                                }
                            }
                            // 令牌快过期时主动断开重连（换新令牌）
                            if (System.currentTimeMillis() > tokenExpiresAt - 5 * 60_000) return@use
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
