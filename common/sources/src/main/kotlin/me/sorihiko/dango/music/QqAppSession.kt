package me.sorihiko.dango.music

import com.google.gson.JsonObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.util.concurrent.CancellationException

/** Completes a phone-confirmed App session; never equates receiving a ticket with a valid login. */
class QqAppSession @JvmOverloads constructor(
    private val transport: (JsonObject, CookieManager) -> JsonObject = { request, cookies ->
        AuthHttp.request(MusicSource.QQ, "https://u.y.qq.com/cgi-bin/musicu.fcg", cookies, request.toString()).json()
    },
    private val pause: () -> Unit = { Thread.sleep(300) },
) {
    private class Candidate(val account: NativeAccount, val key: String)
    private class Reply(val code: Long, val data: JsonObject?)

    /** Read-only enrichment of an existing session; failure must not replace or log out the account. */
    fun profile(account: NativeAccount, active: () -> Boolean): NativeAccount {
        require(account.source == MusicSource.QQ)
        val key = CookieValues.cookie(account.cookies, "qqmusic_key")
        if (key.isEmpty()) return account
        val candidate = Candidate(account, key)
        val reply = verify(candidate, active)
        return if (reply.code == 0L) candidate.accountWithProfile(reply.data) else account
    }

    fun complete(payload: JsonObject, qrId: String, active: () -> Boolean): NativeAccount {
        val values = payload.getAsJsonObject("cookies") ?: throw SourceAccessException("The phone confirmation did not include login credentials. Please retry.")
        val user = values.getAsJsonObject("qqmusic_uin")?.text("value").orEmpty()
        val ticket = values.getAsJsonObject("qqmusic_key")?.text("value").orEmpty()
        val original = candidate(user, ticket, JsonObject(), CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))

        // Some App sessions push a usable music key directly. Re-exchanging such a key
        // can fail with 1000/104401 even though phone authorization already succeeded.
        val first = verify(original, active)
        if (first.code == 0L) return original.accountWithProfile(first.data)
        if (first.code !in setOf(1000L, 104400L, 104401L)) reject(first.code)

        ensureActive(active)
        val params = JsonObject().apply {
            addProperty("musicid", user.toLong()); addProperty("qrCodeID", qrId); addProperty("token", ticket)
            addProperty("loginType", 6); addProperty("needCookie", 1)
        }
        val request = request("music.login.LoginServer", "Login", params).apply {
            getAsJsonObject("comm").addProperty("tmeLoginType", 6)
        }
        // This is a QR-ticket exchange, not an authenticated request with the rejected
        // ticket installed as authst. Keep that cookie jar separate from verification.
        val exchangedCookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val exchanged = call("exchange", request, exchangedCookies, active)
        if (exchanged.code == 0L) {
            val data = exchanged.data ?: reject(-1)
            val id = data.text("musicid").ifEmpty { data.text("str_musicid") }
            val converted = candidate(id, data.text("musickey"), data, exchangedCookies)
            var confirmed = verify(converted, active)
            if (confirmed.code in setOf(1000L, 104400L, 104401L)) {
                pause(); confirmed = verify(converted, active)
            }
            if (confirmed.code == 0L) return converted.accountWithProfile(confirmed.data)
            reject(confirmed.code)
        }

        if (exchanged.code in setOf(1000L, 104400L, 104401L)) {
            // A read-only recheck tolerates short propagation delays without repeating
            // the login mutation or claiming an unverified ticket was successful.
            pause()
            val rechecked = verify(original, active)
            if (rechecked.code == 0L) return original.accountWithProfile(rechecked.data)
            reject(rechecked.code)
        }
        reject(exchanged.code)
    }

    private fun verify(candidate: Candidate, active: () -> Boolean): Reply {
        val account = candidate.account
        val request = request("music.UserInfo.userInfoServer", "GetLoginUserInfo", JsonObject()).apply {
            getAsJsonObject("comm").apply {
                addProperty("uin", account.userId); addProperty("authst", candidate.key)
                addProperty("g_tk", CookieValues.hash33(candidate.key)); addProperty("tmeLoginType", account.loginType)
            }
        }
        val reply = call("verify", request, account.cookies, active)
        if (reply.code != 0L) return reply
        val data = reply.data ?: return Reply(-1, null)
        if (data.entrySet().isEmpty()) return Reply(-1, null)
        // If the endpoint supplies its canonical music ID, it must match this session.
        val identity = data.text("musicid").ifEmpty { data.text("str_musicid") }
        if (identity.isNotEmpty() && identity != account.userId) return Reply(-2, null)
        return reply
    }

    private fun request(module: String, method: String, params: JsonObject) = JsonObject().apply {
        add("comm", JsonObject().apply {
            // Match the desktop profile registered in CreateQRCode, not its generic QR service profile.
            addProperty("ct", 19); addProperty("cv", 2201)
        })
        add("req_0", JsonObject().apply { addProperty("module", module); addProperty("method", method); add("param", params) })
    }

    private fun call(phase: String, request: JsonObject, cookies: CookieManager, active: () -> Boolean): Reply {
        ensureActive(active)
        val response = try { transport(request, cookies) } catch (_: Exception) {
            java.util.logging.Logger.getLogger("MineKUmusic.sources").warning("QQ Music App session phase=$phase outcome=network-error")
            throw SourceAccessException("Phone confirmed. The desktop login could not be verified. Please retry.")
        }
        ensureActive(active)
        val block = response.get("req_0")?.takeIf { it.isJsonObject }?.asJsonObject
        val code = when {
            !response.has("code") -> -1L
            response.number("code") != 0L -> response.number("code")
            block == null || !block.has("code") -> -1L
            else -> block.number("code")
        }
        // Phase and numeric codes only: never log a cookie, body, identity, ticket or key.
        java.util.logging.Logger.getLogger("MineKUmusic.sources").info("QQ Music App session phase=$phase code=$code")
        return Reply(code, block?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject)
    }

    private fun candidate(id: String, key: String, data: JsonObject, cookies: CookieManager): Candidate {
        if (!id.matches(Regex("[1-9][0-9]{0,18}")) || id.toLongOrNull() == null || key.isEmpty() || key.any { it == '\r' || it == '\n' || it == ';' }) {
            throw SourceAccessException("The phone confirmation did not include login credentials. Please retry.")
        }
        val type = if (data.has("loginType")) data.number("loginType").toInt() else if (key.startsWith("W_X")) 1 else 2
        AuthHttp.acceptCookies(MusicSource.QQ, URI("https://y.qq.com/"), cookies, mapOf("Set-Cookie" to listOf(
            "qqmusic_uin=$id; Domain=.qq.com; Path=/; Secure", "qqmusic_key=$key; Domain=.qq.com; Path=/; Secure",
            "login_type=${if (type == 1) 2 else 1}; Domain=.qq.com; Path=/; Secure")))
        return Candidate(NativeAccount(MusicSource.QQ, id, nickname(data), "", "", cookies, type, avatar(data)), key)
    }

    private fun Candidate.accountWithProfile(profile: JsonObject?): NativeAccount {
        val name = profile?.let(::nickname).orEmpty().ifEmpty { account.nickname }
        val image = profile?.let(::avatar).orEmpty().ifEmpty { account.avatarUrl }
        return NativeAccount(account.source, account.userId, name, account.token, account.deviceId, account.cookies, account.loginType, image)
    }

    private fun nickname(data: JsonObject) = profileField(data, listOf("nick", "nickname", "nickName"))

    private fun avatar(data: JsonObject): String {
        val value = profileField(data, listOf("headurl", "headUrl", "logo", "avatarUrl", "avatar")).trim()
        return try {
            val url = if (value.startsWith("//")) "https:$value" else if (value.startsWith("http://")) "https:" + value.substring(5) else value
            val uri = URI(url)
            if (uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null) url else ""
        } catch (_: Exception) { "" }
    }

    private fun profileField(data: JsonObject, fields: List<String>): String {
        for (name in fields) data.text(name).takeIf { it.isNotEmpty() }?.let { return it }
        // GetLoginUserInfo nests the QQ Music profile under data.info, rather than
        // returning nick at the top level like the Login exchange sometimes does.
        for (name in listOf("info", "userInfo", "userinfo", "base")) {
            val child = data.get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for (field in fields) child.text(field).takeIf { it.isNotEmpty() }?.let { return it }
            val base = child.get("base")?.takeIf { it.isJsonObject }?.asJsonObject
            if (base != null) for (field in fields) base.text(field).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun ensureActive(active: () -> Boolean) { if (!active()) throw CancellationException("QR session cancelled") }
    private fun reject(code: Long): Nothing = throw SourceAccessException(when (code) {
        20279L -> "QQ Music device limit reached. Remove an unused device in the app and retry."
        104604L -> "Too many login attempts. Please try again later."
        else -> "Phone confirmed. The desktop login could not be verified. Please retry."
    })
}
