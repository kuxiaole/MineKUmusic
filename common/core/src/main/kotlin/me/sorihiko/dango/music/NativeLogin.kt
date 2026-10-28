package me.sorihiko.dango.music

import com.google.gson.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/** Native web QR flows. Requests, cookies and credentials stay isolated for each provider. */
object NativeLogin {
    @Volatile var source = MusicSource.QQ; private set
    @Volatile var state = NeteaseLogin.State.IDLE; private set
    @Volatile var qrImage: BufferedImage? = null; private set
    @Volatile var qrVersion = 0; private set
    @Volatile var message = ""; private set
    private val lock = Any()
    @Volatile private var session = 0
    @Volatile private var appChannel: ProviderQrLogin? = null

    fun start(provider: MusicSource) {
        require(provider != MusicSource.NETEASE)
        val current = synchronized(lock) {
            appChannel?.close(); appChannel = null
            ++session
            source = provider; state = NeteaseLogin.State.LOADING; qrImage = null; message = ""
            session
        }
        Thread({
            try {
                if (provider == MusicSource.QQ) qq(current) else kugou(current)
            } catch (error: Exception) {
                update(current) {
                    state = NeteaseLogin.State.ERROR
                    message = if (error is SourceAccessException) error.message.orEmpty() else "Login request failed. Refresh the QR code and try again."
                }
            }
        }, "Dango-${provider.key}-Login").apply { isDaemon = true; start() }
    }

    fun cancel() = synchronized(lock) {
        appChannel?.close(); appChannel = null
        ++session; qrImage = null
        if (state != NeteaseLogin.State.SUCCESS) state = NeteaseLogin.State.IDLE
    }

    private fun update(current: Int, apply: () -> Unit) = synchronized(lock) { if (current == session) apply() }
    private fun success(current: Int, account: NativeAccount) = update(current) {
        NativeAccounts.save(account)
        state = NeteaseLogin.State.SUCCESS
    }
    private fun show(current: Int, bytes: ByteArray) {
        require(bytes.size <= 1024 * 1024)
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Invalid QR image")
        require(image.width in 32..1024 && image.height in 32..1024)
        update(current) { qrImage = image; qrVersion++; state = NeteaseLogin.State.WAITING }
    }

    private fun qq(current: Int) {
        val login = QqQrLogin()
        val attached = synchronized(lock) {
            if (current == session) { appChannel = login; true } else false
        }
        if (!attached) { login.close(); return }
        try {
            val account = login.run(object : ProviderQrLogin.Observer {
                override fun active() = current == session
                override fun image(png: ByteArray) { if (active()) show(current, png) }
                override fun state(state: String) = update(current) {
                    this@NativeLogin.state = if (state == "waiting") NeteaseLogin.State.WAITING else NeteaseLogin.State.SCANNED
                    message = if (state == "verifying") "Phone confirmed. Verifying desktop login..." else ""
                }
            })
            success(current, account)
        } catch (_: java.util.concurrent.CancellationException) {
            update(current) { state = NeteaseLogin.State.EXPIRED }
        } finally {
            login.close()
            synchronized(lock) { if (appChannel === login) appChannel = null }
        }
    }

    private fun kugou(current: Int) {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val device = UUID.randomUUID().toString().replace("-", "")
        val root = KugouWeb.get("https://login-user.kugou.com/v2/qrcode", mapOf("clientver" to "1000", "type" to "1", "plat" to "4",
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1014&"), device, cookies)
        check(root.number("status") == 1L)
        val data = root.getAsJsonObject("data") ?: error("No Kugou QR")
        val key = data.text("qrcode")
        check(key.isNotEmpty())
        val image = data.text("qrcode_img")
        check(image.startsWith("data:image/png;base64,"))
        show(current, Base64.getDecoder().decode(image.substringAfter(',')))
        val started = System.currentTimeMillis()
        while (current == session && System.currentTimeMillis() - started < 180_000) {
            Thread.sleep(2000)
            if (current != session) return
            val poll = KugouWeb.get("https://login-user.kugou.com/v2/get_userinfo_qrcode",
                mapOf("clientver" to "1000", "plat" to "4", "qrcode" to key), device, cookies)
            check(poll.number("status") == 1L)
            val state = poll.getAsJsonObject("data") ?: error("No Kugou QR status")
            when (state.number("status")) {
                1L -> update(current) { this.state = NeteaseLogin.State.WAITING }
                2L -> update(current) { this.state = NeteaseLogin.State.SCANNED }
                4L -> {
                    val info = state.getAsJsonObject("userinfo") ?: state.getAsJsonObject("user_info") ?: state
                    val id = info.text("userid").ifEmpty { state.text("userid") }
                    val token = info.text("token").ifEmpty { state.text("token") }
                    check(id.isNotEmpty() && token.isNotEmpty())
                    if (current != session) return
                    val exchanged = KugouWeb.tokenLogin(id, token, device, cookies)
                    val accountData = exchanged.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    val webToken = KugouWeb.cookiePart(cookies, "t").ifEmpty { accountData?.text("token").orEmpty() }
                    check(webToken.isNotEmpty())
                    val webId = KugouWeb.cookiePart(cookies, "KugooID").ifEmpty { id }
                    val nickname = accountData?.text("nickname").orEmpty().ifEmpty { info.text("nickname").ifEmpty { state.text("nickname") } }
                    val avatar = KugouCatalog.image(accountData?.text("pic").orEmpty().ifEmpty { info.text("pic").ifEmpty { info.text("picurl") } })
                    success(current, NativeAccount(MusicSource.KUGOU, webId, nickname, webToken, device, cookies, avatarUrl = avatar))
                    return
                }
                else -> { update(current) { this.state = NeteaseLogin.State.EXPIRED }; return }
            }
        }
        update(current) { state = NeteaseLogin.State.EXPIRED }
    }
}
