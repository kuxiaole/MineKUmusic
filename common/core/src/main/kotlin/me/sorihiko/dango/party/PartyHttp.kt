package me.sorihiko.dango.party

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.URL

/** 一起听歌的 HTTP 请求和 SSE 长连接，都按 [PartyProxy] 的设置走 */
object PartyHttp {

    /** [relayed]：响应里有中转 Worker 加的 X-Dango-Relay，说明请求确实经过了中转 */
    class Result(val code: Int, val body: ByteArray?, val etag: String?, val relayed: Boolean = false)

    /** 建立连接时就失败了，请求肯定还没发出去（可以安全地换一条路再发） */
    class NotSent(cause: IOException) : IOException(cause.toString(), cause)

    const val RELAY_HEADER = "X-Dango-Relay"

    /** 建立连接最多等多久：被墙时通常一直没回应，等太久只会让玩家干等（经本地代理时这一步很快） */
    private const val CONNECT_TIMEOUT_MS = 5000

    /**
     * 发一个请求，返回状态码、成功时的响应体和 ETag。网络错误抛 [IOException]。
     * 有请求体时用固定长度的流式发送：HttpURLConnection 在这种模式下不会自己悄悄重发请求，
     * 写请求最多送出一次。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun send(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String> = emptyMap(),
    ): Result {
        val conn = connect(URL(url)) { c ->
            // HttpURLConnection 不支持 PATCH，用 Firebase 认的 X-HTTP-Method-Override
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                c.requestMethod = method
            }
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = 10000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = try {
                conn.responseCode
            } catch (e: HttpRetryException) {
                // 流式发送时遇到认证 / 重定向，不重发，按状态码处理
                return Result(e.responseCode(), null, null)
            }
            val bytes = if (code in 200..299) conn.inputStream.use { it.readBytes() } else null
            return Result(code, bytes, conn.getHeaderField("ETag"), conn.getHeaderField(RELAY_HEADER) != null)
        } finally {
            conn.disconnect()
        }
    }

    /** 打开 SSE 长连接（GET），调用方自己看状态码、读 [readSse]、用完 disconnect */
    @JvmStatic
    @Throws(IOException::class)
    fun openStream(url: String, readTimeoutMs: Int): HttpURLConnection =
        connect(URL(url)) { c ->
            c.setRequestProperty("Accept", "text/event-stream")
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = true
        }

    /** 按代理设置建立连接；连接阶段的失败包成 [NotSent]（手动代理的问题仍是 [PartyProxy.ProxyUnavailable]） */
    private fun connect(url: URL, setup: (HttpURLConnection) -> Unit): HttpURLConnection = try {
        PartyProxy.connect(url, setup)
    } catch (e: PartyProxy.ProxyUnavailable) {
        throw e
    } catch (e: IOException) {
        throw NotSent(e)
    }

    /**
     * 读 SSE 流：每条 data 行连同它前面的 event 名交给 [onData]。
     * [keepGoing] 在每读一行之前检查，它或 [onData] 返回 false 时停止；流结束时也返回。
     */
    @JvmStatic
    fun readSse(reader: BufferedReader, keepGoing: () -> Boolean, onData: (String, String) -> Boolean) {
        var event = ""
        while (keepGoing()) {
            val line = reader.readLine() ?: return
            when {
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> if (!onData(event, line.substring(5).trim())) return
            }
        }
    }
}
