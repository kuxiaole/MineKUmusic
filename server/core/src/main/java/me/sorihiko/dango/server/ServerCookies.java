package me.sorihiko.dango.server;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Browser JSON exports; each source has a separate file and domain boundary. IO executor only. */
final class ServerCookies {
    private ServerCookies() {}
    static Path path(Path directory, String source) {
        if (!Set.of("netease", "qq").contains(source)) throw new IllegalArgumentException("Unsupported music source or song ID");
        return directory.resolve(source + "-cookie.json");
    }
    static void migrate(Path directory) throws IOException {
        if (directory == null) return;
        Path old = directory.resolve("cookie.json"), target = path(directory, "netease");
        if (Files.isRegularFile(old) && !Files.exists(target)) {
            try { Files.copy(old, target); } catch (FileAlreadyExistsException ignored) { }
        }
    }
    static CookieManager load(Path directory, String source) throws IOException {
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        if (directory == null) return manager;
        if (source.equals("netease")) migrate(directory);
        Path file = path(directory, source);
        if (!Files.exists(file)) return manager;
        String data;
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw invalid(source);
            data = new String(bytes, StandardCharsets.UTF_8);
        }
        if (data.startsWith("\uFEFF")) data = data.substring(1);
        try {
            JsonArray rows = JsonParser.parseString(data).getAsJsonArray();
            String origin = source.equals("qq") ? "https://y.qq.com/" : "https://music.163.com/";
            int total = 0;
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                String name = field(row, "name", null), value = field(row, "value", null);
                if (name == null || value == null || !name.matches("[A-Za-z0-9_!#$%&'*+.^|~-]+")
                        || value.chars().anyMatch(c -> c < 0x21 || c > 0x7e || c == ';')) throw invalid(source);
                String domain = field(row, "domain", source.equals("qq") ? "qq.com" : "music.163.com").toLowerCase(Locale.ROOT);
                String plain = domain.startsWith(".") ? domain.substring(1) : domain;
                boolean accepted = source.equals("qq") ? Set.of("qq.com", "y.qq.com", "u.y.qq.com", "c.y.qq.com").contains(plain)
                        : Set.of("163.com", "music.163.com").contains(plain);
                if (!accepted) continue;
                if (row.has("expirationDate") && row.get("expirationDate").getAsDouble() <= System.currentTimeMillis() / 1000.0) continue;
                total += name.length() + value.length() + 3;
                if (total > 16 * 1024) throw invalid(source);
                HttpCookie cookie = new HttpCookie(name, value);
                cookie.setDomain("." + plain); cookie.setPath(field(row, "path", "/")); cookie.setVersion(0); cookie.setSecure(true);
                manager.getCookieStore().add(URI.create(origin), cookie);
            }
            return manager;
        } catch (RuntimeException e) { throw invalid(source); }
    }
    static void save(Path directory, String source, CookieManager cookies) throws IOException {
        JsonArray rows = new JsonArray();
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            if (cookie.hasExpired()) continue;
            JsonObject row = new JsonObject();
            row.addProperty("name", cookie.getName()); row.addProperty("value", cookie.getValue());
            row.addProperty("domain", cookie.getDomain()); row.addProperty("path", cookie.getPath());
            row.addProperty("secure", true); row.addProperty("httpOnly", cookie.isHttpOnly());
            if (cookie.getMaxAge() >= 0) row.addProperty("expirationDate", System.currentTimeMillis() / 1000 + cookie.getMaxAge());
            rows.add(row);
        }
        if (rows.isEmpty()) throw invalid(source);
        Files.createDirectories(directory);
        Path target = path(directory, source), temporary = Files.createTempFile(directory, source + "-cookie-", ".tmp");
        try {
            Files.writeString(temporary, rows.toString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String field(JsonObject row, String key, String fallback) {
        JsonElement value = row.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }
    private static IOException invalid(String source) { return new IOException("Invalid server " + source + "-cookie.json; expected a browser Cookie JSON array"); }
}
