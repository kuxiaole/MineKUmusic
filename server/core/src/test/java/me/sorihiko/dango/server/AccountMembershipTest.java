package me.sorihiko.dango.server;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.sorihiko.dango.server.AccountMembership.Status.*;

class AccountMembershipTest {
    private static final long NOW = 1_800_000_000_000L;
    private JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private JsonObject identity() { return json("{\"code\":200,\"account\":{\"id\":123}}"); }
    @Test void neteaseRequiresItsAuthenticatedIdentityAndAnUnexpiredMusicEntitlement() {
        for (String kind : new String[]{"associator", "musicPackage"}) {
            var vip = json("{\"code\":200,\"data\":{\"" + kind + "\":{\"expireTime\":1800000060000}}}");
            var result = AccountMembership.netease(identity(), vip, NOW);
            assertEquals(VALID, result.status()); assertEquals("123", result.userId());
            assertEquals(NON_MEMBER, AccountMembership.netease(identity(), vip, NOW + 60_000).status());
            assertEquals(INVALID, AccountMembership.netease(json("{\"code\":200,\"account\":null}"), vip, NOW).status());
        }
        assertEquals(TEMPORARY, AccountMembership.netease(identity(), json("{\"code\":200,\"data\":{\"redVipLevel\":9}}"), NOW).status());
        assertEquals(NON_MEMBER, AccountMembership.netease(identity(), json("{\"code\":200,\"data\":{\"associator\":{\"expireTime\":1800000060000,\"isValid\":false}}}"), NOW).status());
        assertEquals(INVALID, AccountMembership.netease(identity(), json("{\"code\":301}"), NOW).status());
    }
    @Test void qqRequiresMusicVipAndExpiryNotJustAProfileOrBadge() {
        var vip = json("{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"identity\":{\"HugeVip\":1},\"userinfo\":{\"expire\":1800000060}}}}");
        assertEquals(VALID, AccountMembership.qq("123", vip, NOW).status());
        assertEquals(NON_MEMBER, AccountMembership.qq("123", vip, NOW + 60_000).status());
        vip.getAsJsonObject("req_0").getAsJsonObject("data").getAsJsonObject("identity").addProperty("HugeVip", 0);
        assertEquals(NON_MEMBER, AccountMembership.qq("123", vip, NOW).status());
        assertEquals(TEMPORARY, AccountMembership.qq("123", json("{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"nickname\":\"Fixture\"}}}"), NOW).status());
        assertEquals(INVALID, AccountMembership.qq("123", json("{\"code\":0,\"req_0\":{\"code\":104401}}"), NOW).status());
        assertEquals(NOW, AccountMembership.expiry("1800000000"));
        assertEquals(NOW, AccountMembership.expiry("1800000000000"));
        assertTrue(AccountMembership.expiry("2030-01-01 00:00:00") > NOW);
        assertEquals(0, AccountMembership.expiry("0000-00-00 00:00:00"));
    }
}
