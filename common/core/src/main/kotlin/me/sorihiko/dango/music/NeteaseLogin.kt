package me.sorihiko.dango.music

import com.google.gson.JsonParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import me.sorihiko.dango.DangoLogger

import java.awt.image.BufferedImage
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云扫码登录（和网页版一样）：
 *   1. 申请一个二维码 key
 *   2. 把 https://music.163.com/login?codekey=KEY 画成二维码，用手机网易云 App 扫
 *   3. 轮询扫码状态：801 等待扫码 / 802 已扫码待确认 / 803 成功（响应里带登录 Cookie）/ 800 过期
 * 成功后自动把 Cookie 写进 config/dangomusic/netease_cookie.txt
 */
object NeteaseLogin {

    enum class State { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var qrImage: BufferedImage? = null
        private set
    /** 每生成一张新二维码 +1，界面据此重新上传纹理 */
    @Volatile var qrVersion = 0
        private set

    @Volatile private var session = 0

    fun start() {
        val my = ++session
        state = State.LOADING
        qrImage = null
        Thread({ run(my) }, "Dango-NeteaseLogin").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        ++session
        if (state != State.SUCCESS) state = State.IDLE
        qrImage = null
    }

    private fun run(my: Int) {
        try {
            val keyResp = weapi("https://music.163.com/weapi/login/qrcode/unikey", """{"type":1}""")
            val unikey = keyResp?.first?.let { JsonParser().parse(it).asJsonObject.get("unikey")?.asString }
            if (unikey.isNullOrEmpty()) {
                if (my == session) state = State.ERROR
                return
            }
            if (my != session) return
            qrImage = makeQr("https://music.163.com/login?codekey=$unikey", 256)
            qrVersion++
            state = State.WAITING

            while (my == session) {
                Thread.sleep(2000)
                if (my != session) return
                val resp = weapi(
                    "https://music.163.com/weapi/login/qrcode/client/login",
                    """{"key":"$unikey","type":1}""",
                ) ?: continue
                val code = JsonParser().parse(resp.first).asJsonObject.get("code")?.asInt ?: continue
                when (code) {
                    801 -> state = State.WAITING
                    802 -> state = State.SCANNED
                    800 -> {
                        state = State.EXPIRED
                        return
                    }
                    803 -> {
                        val cookie = extractCookie(resp.second)
                        if (cookie.contains("MUSIC_U=")) {
                            NeteaseApi.saveCookie(cookie)
                            state = State.SUCCESS
                        } else {
                            state = State.ERROR
                        }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            DangoLogger.warn("NetEase QR login failed: {}", e.toString())
            if (my == session) state = State.ERROR
        }
    }

    /** 从 Set-Cookie 里挑出需要的登录 Cookie */
    private fun extractCookie(setCookies: List<String>): String {
        val wanted = setOf("MUSIC_U", "__csrf", "MUSIC_A_T", "MUSIC_R_T", "NMTID")
        val parts = mutableListOf<String>()
        for (sc in setCookies) {
            val kv = sc.substringBefore(';').trim()
            val name = kv.substringBefore('=')
            if (name in wanted && kv.length > name.length + 1) parts += kv
        }
        return parts.joinToString("; ")
    }

    private fun makeQr(text: String, size: Int): BufferedImage {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            img.setRGB(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        return img
    }

    fun weapi(url: String, json: String, cookie: String = "os=pc; appver=2.10.13"): Pair<String, List<String>>? =
        NeteaseWeb.weapi(url, json, cookie)
}
