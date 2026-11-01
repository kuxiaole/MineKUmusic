package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.music.*;
import java.net.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static me.sorihiko.dango.protocol.MusicProtocol.*;

/** Checks the authenticated identity and music entitlement separately. No song URL is a VIP proof. */
final class AccountMembership {
    enum Status { VALID, INVALID, NON_MEMBER, TEMPORARY }
    record Result(Status status, String userId, long expiresAt) {
        static Result temporary() { return new Result(Status.TEMPORARY, "", 0); }
        static Result invalid() { return new Result(Status.INVALID, "", 0); }
    }
    Result check(NativeAccount account) {
        try {
            if (account.getSource() == MusicSource.NETEASE) {
                if (CookieValues.cookie(account.getCookies(), "MUSIC_U").isEmpty()) return Result.invalid();
                var response = AuthHttp.INSTANCE.request(MusicSource.NETEASE, "https://music.163.com/api/nuser/account/get", account.getCookies());
                if (response.getCode() == 401) return Result.invalid();
                JsonObject identity = response.json();
                long code = number(identity, "code", -1);
                if (code == 301 || code == 401) return Result.invalid();
                if (code != 200) return Result.temporary();
                String user = identity(identity);
                if (user.isEmpty()) return Result.invalid();
                String cookie = String.join("; ", account.getCookies().getCookieStore().getCookies().stream()
                        .filter(c -> !c.hasExpired()).map(c -> c.getName() + "=" + c.getValue()).toList());
                JsonObject params = new JsonObject(); params.addProperty("userId", user);
                params.addProperty("csrf_token", CookieValues.cookie(account.getCookies(), "__csrf"));
                var reply = NeteaseWeb.INSTANCE.weapi("https://music.163.com/weapi/music-vip-membership/client/vip/info", params.toString(), cookie);
                if (reply == null) return Result.temporary();
                return netease(identity, JsonParser.parseString(reply.getFirst()).getAsJsonObject(), System.currentTimeMillis());
            }
            if (account.getSource() != MusicSource.QQ || account.getUserId().isEmpty()
                    || CookieValues.cookie(account.getCookies(), "qqmusic_key").isEmpty()) return Result.invalid();
            JsonObject identity = qqCall(account, "music.UserInfo.userInfoServer", "GetLoginUserInfo");
            if (loginRejected(identity)) return Result.invalid();
            JsonObject profile = data(identity);
            if (profile == null || profile.entrySet().isEmpty()) return Result.temporary();
            String user = string(profile, "musicid", string(profile, "str_musicid", account.getUserId()));
            if (!user.equals(account.getUserId())) return Result.invalid();
            return qq(account.getUserId(), qqCall(account, "VipLogin.VipLoginInter", "vip_login_base"), System.currentTimeMillis());
        } catch (Exception ignored) { return Result.temporary(); }
    }
    private JsonObject qqCall(NativeAccount account, String module, String method) {
        String key = CookieValues.cookie(account.getCookies(), "qqmusic_key");
        JsonObject comm = new JsonObject();
        comm.addProperty("ct", 19); comm.addProperty("cv", 2201); comm.addProperty("uin", account.getUserId());
        comm.addProperty("authst", key); comm.addProperty("g_tk", CookieValues.hash33(key)); comm.addProperty("tmeLoginType", account.getLoginType());
        JsonObject block = new JsonObject(); block.addProperty("module", module); block.addProperty("method", method); block.add("param", new JsonObject());
        JsonObject request = new JsonObject(); request.add("comm", comm); request.add("req_0", block);
        return AuthHttp.INSTANCE.request(MusicSource.QQ, "https://u.y.qq.com/cgi-bin/musicu.fcg", account.getCookies(), request.toString(), "application/json").json();
    }
    private static boolean loginRejected(JsonObject response) {
        long code = number(response, "code", -1);
        if (code == 0) code = number(object(response, "req_0"), "code", -1);
        return code == 1000 || code == 104400 || code == 104401;
    }
    private static JsonObject data(JsonObject response) {
        if (number(response, "code", -1) != 0) return null;
        JsonObject block = object(response, "req_0");
        if (number(block, "code", -1) != 0) return null;
        JsonObject data = object(block, "data");
        return data.has("code") && number(data, "code", -1) != 0 ? null : data;
    }
    static Result netease(JsonObject identity, JsonObject response, long now) {
        long code = number(identity, "code", -1);
        if (code == 301 || code == 401) return Result.invalid();
        if (code != 200) return Result.temporary();
        String user = identity(identity);
        if (user.isEmpty()) return Result.invalid();
        code = number(response, "code", -1);
        if (code == 301 || code == 401) return Result.invalid();
        if (code != 200) return Result.temporary();
        JsonObject data = object(response, "data");
        if (!data.has("associator") && !data.has("musicPackage")) return Result.temporary();
        long until = 0;
        for (String kind : List.of("associator", "musicPackage")) {
            JsonObject entitlement = object(data, kind);
            // These two blocks represent music rights, not listening-book or cosmetic memberships.
            if (!entitlement.has("isValid") || bool(entitlement, "isValid", false))
                until = Math.max(until, number(entitlement, "expireTime", 0));
        }
        return new Result(until > now ? Status.VALID : Status.NON_MEMBER, user, until);
    }
    static Result qq(String user, JsonObject response, long now) {
        if (loginRejected(response)) return Result.invalid();
        JsonObject data = data(response);
        if (data == null || !data.has("identity") || !data.has("userinfo")) return Result.temporary();
        JsonObject identity = object(data, "identity");
        boolean active = number(identity, "vip", 0) > 0 || number(identity, "HugeVip", 0) > 0 || number(data, "svip", 0) > 0;
        long until = expiry(string(object(data, "userinfo"), "expire", ""));
        if (number(identity, "HugeVip", 0) > 0) until = Math.max(until, expiry(string(identity, "HugeVipEnd", "")));
        if (active && until == 0) return Result.temporary(); // Unknown expiry must not grant a permanent discount.
        return new Result(active && until > now ? Status.VALID : Status.NON_MEMBER, user, until);
    }
    private static String identity(JsonObject root) {
        long id = number(object(root, "account"), "id", number(object(root, "profile"), "userId", 0));
        return id > 0 ? Long.toString(id) : "";
    }
    private static JsonObject object(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    static long expiry(String value) {
        try {
            if (value.matches("[0-9]{10,13}")) { long n = Long.parseLong(value); return n < 100_000_000_000L ? n * 1000 : n; }
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        } catch (Exception ignored) { return 0; }
    }
}
