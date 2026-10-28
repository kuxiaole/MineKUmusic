package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.CookieManager
import java.net.URI
import java.util.Base64
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO

interface ProviderQrLogin : Closeable {
    interface Observer {
        fun active(): Boolean
        fun image(png: ByteArray)
        fun state(state: String)
    }
    fun run(observer: Observer): NativeAccount
}

class QqQrLogin : ProviderQrLogin {
    private val channel = QqAppChannel()
    override fun close() = channel.close()
    override fun run(observer: ProviderQrLogin.Observer): NativeAccount {
        val request = JsonObject().apply {
            add("comm", JsonObject().apply { addProperty("ct", 23); addProperty("cv", 0) })
            add("req_0", JsonObject().apply {
                addProperty("module", "music.login.LoginServer"); addProperty("method", "CreateQRCode")
                add("param", JsonObject().apply { addProperty("tmeAppID", "qqmusic"); addProperty("ct", 19); addProperty("cv", 2201) })
            })
        }
        try {
            val response = AuthHttp.request(MusicSource.QQ, "https://u.y.qq.com/cgi-bin/musicu.fcg", CookieManager(), request.toString()).json()
            val block = response.obj("req_0")
            check(response.has("code") && response.number("code") == 0L && block?.number("code") == 0L)
            val data = block?.obj("data") ?: error("Missing QR response")
            val id = data.text("qrcodeID")
            require(id.isNotEmpty() && data.text("qrcode").length <= 512 * 1024)
            val png = Base64.getDecoder().decode(data.text("qrcode").substringAfter(','))
            ensure(observer)
            channel.connect(id)
            ensure(observer)
            observer.image(png)
            val deadline = System.currentTimeMillis() + 180000
            while (observer.active() && System.currentTimeMillis() < deadline) {
                val event = channel.next(deadline) ?: break
                ensure(observer)
                when (event.type) {
                    "scanned" -> observer.state("scanned")
                    "timeout" -> break
                    "canceled", "loginFailed" -> throw SourceAccessException("QQ Music rejected the login. Please check your account and try again.")
                    "cookies" -> {
                        observer.state("verifying")
                        return QqAppSession().complete(JsonParser().parse(String(event.payload, Charsets.UTF_8)).asJsonObject, id) { observer.active() }
                    }
                }
            }
            throw CancellationException("expired")
        } finally { close() }
    }
}

class NeteaseQrLogin : ProviderQrLogin {
    @Volatile private var closed = false
    override fun close() { closed = true }
    override fun run(observer: ProviderQrLogin.Observer): NativeAccount {
        val active = { !closed && observer.active() }
        val created = NeteaseWeb.weapi("https://music.163.com/weapi/login/qrcode/unikey", "{\"type\":1}")
            ?: error("QR creation failed")
        val key = JsonParser().parse(created.first).asJsonObject.text("unikey")
        require(key.isNotEmpty())
        ensure(observer)
        observer.image(qr("https://music.163.com/login?codekey=$key"))
        val deadline = System.currentTimeMillis() + 180000
        while (active() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            if (!active()) throw CancellationException("cancelled")
            val body = JsonObject().apply { addProperty("key", key); addProperty("type", 1) }
            val reply = NeteaseWeb.weapi("https://music.163.com/weapi/login/qrcode/client/login", body.toString()) ?: continue
            if (!active()) throw CancellationException("cancelled")
            when (JsonParser().parse(reply.first).asJsonObject.number("code")) {
                801L -> observer.state("waiting")
                802L -> observer.state("scanned")
                800L -> break
                803L -> {
                    observer.state("verifying")
                    val cookies = CookieManager()
                    AuthHttp.acceptCookies(MusicSource.NETEASE, URI("https://music.163.com/"), cookies, mapOf("Set-Cookie" to reply.second))
                    check(CookieValues.cookie(cookies, "MUSIC_U").isNotEmpty())
                    val profile = AuthHttp.request(MusicSource.NETEASE, "https://music.163.com/api/nuser/account/get", cookies).json()
                    val id = profile.obj("account")?.number("id") ?: profile.obj("profile")?.number("userId") ?: 0L
                    check(profile.number("code") == 200L && id > 0)
                    if (!active()) throw CancellationException("cancelled")
                    return NativeAccount(MusicSource.NETEASE, id.toString(), profile.obj("profile")?.text("nickname").orEmpty(), "", "", cookies)
                }
            }
        }
        throw CancellationException("expired")
    }
    private fun qr(value: String): ByteArray {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256, mapOf(EncodeHintType.MARGIN to 1))
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) image.setRGB(x, y, if (matrix.get(x, y)) -16777216 else -1)
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }
}

private fun ensure(observer: ProviderQrLogin.Observer) { if (!observer.active()) throw CancellationException("cancelled") }
