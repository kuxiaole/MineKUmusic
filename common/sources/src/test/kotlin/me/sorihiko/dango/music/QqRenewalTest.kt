package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.util.concurrent.CancellationException

class QqRenewalTest {
    private fun json(value: String) = JsonParser().parse(value).asJsonObject
    private fun response(data: String, code: Int = 0) = JsonObject().apply {
        addProperty("code", 0)
        add("req_0", JsonObject().apply { addProperty("code", code); add("data", json(data)) })
    }
    private val payload get() = json("""{"cookies":{"qqmusic_uin":{"value":"12345"},"qqmusic_key":{"value":"fixture-phone-key"}}}""")
    private fun account(): NativeAccount {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        AuthHttp.acceptCookies(MusicSource.QQ, URI("https://y.qq.com/"), cookies, mapOf("Set-Cookie" to listOf(
            "qqmusic_key=fixture-old-key; Domain=.qq.com; Path=/; Secure")))
        return NativeAccount(MusicSource.QQ, "12345", "Fixture", "", "", cookies, renewal =
            json("""{"refresh_key":"fixture-refresh","refresh_token":"fixture-token","issuedAt":1}"""))
    }

    @Test fun qrExchangeObtainsRenewableCredentialsBeforeVerifyingIdentity() {
        val methods = mutableListOf<String>()
        val session = QqAppSession({ request, _ ->
            val method = request.getAsJsonObject("req_0").text("method"); methods += method
            if (method == "Login") response("""{"musicid":"12345","musickey":"fixture-new-key","refresh_key":"fixture-refresh","refresh_token":"fixture-token"}""")
            else response("""{"musicid":"12345","info":{"nick":"Fixture"}}""")
        }, {})
        val account = session.complete(payload, "fixture-qr") { true }
        assertEquals(listOf("Login", "GetLoginUserInfo"), methods)
        assertEquals("fixture-new-key", CookieValues.cookie(account.cookies, "qqmusic_key"))
        assertEquals("fixture-refresh", account.renewal?.text("refresh_key"))
        assertEquals("Fixture", account.nickname)
        assertFalse(QqRenewal.due(account))
    }

    @Test fun failedExchangeCanStillUseAnIndependentlyVerifiedPhoneKey() {
        val session = QqAppSession({ request, _ ->
            if (request.getAsJsonObject("req_0").text("method") == "Login") response("{}", 104401)
            else response("""{"musicid":"12345","nick":"Fixture"}""")
        }, {})
        val account = session.complete(payload, "fixture-qr") { true }
        assertNull(account.renewal)
        assertEquals("fixture-phone-key", CookieValues.cookie(account.cookies, "qqmusic_key"))
    }

    @Test fun renewalPreservesIdentityAndKeepsOldCookiesUntouched() {
        val original = account()
        assertTrue(QqRenewal.due(original))
        val session = QqAppSession({ request, _ ->
            val block = request.getAsJsonObject("req_0")
            if (block.text("method") == "Login") {
                assertEquals(2, block.getAsJsonObject("param").number("loginMode").toInt())
                assertEquals("fixture-refresh", block.getAsJsonObject("param").text("refresh_key"))
                response("""{"musicid":"12345","musickey":"fixture-fresh-key","refresh_key":"fixture-next-refresh"}""")
            } else response("""{"musicid":"12345","nick":"Fixture"}""")
        }, {})
        val renewed = session.renew(original)!!
        assertEquals(original.userId, renewed.userId)
        assertEquals("fixture-token", renewed.renewal?.text("refresh_token"))
        assertEquals("fixture-fresh-key", CookieValues.cookie(renewed.cookies, "qqmusic_key"))
        assertEquals("fixture-old-key", CookieValues.cookie(original.cookies, "qqmusic_key"))
        assertFalse(QqRenewal.due(renewed))
    }

    @Test fun renewalCannotChangeTheAccountOwnerOrAcceptFailedVerification() {
        for (wrongOwner in listOf(true, false)) {
            val session = QqAppSession({ request, _ ->
                if (request.getAsJsonObject("req_0").text("method") == "Login")
                    response("""{"musicid":"${if (wrongOwner) 99999 else 12345}","musickey":"fixture-new","refresh_key":"fixture-refresh"}""")
                else response("{}", 104401)
            }, {})
            assertNull(session.renew(account()))
        }
    }

    @Test fun cancelledQrCannotExchangeOrReturnCredentials() {
        val session = QqAppSession({ _, _ -> fail("Cancelled QR performed a request"); response("{}") }, {})
        try { session.complete(payload, "fixture-qr") { false }; fail("Accepted cancelled QR") }
        catch (_: CancellationException) { }
    }
}
