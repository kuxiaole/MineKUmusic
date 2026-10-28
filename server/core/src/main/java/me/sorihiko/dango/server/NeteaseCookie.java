package me.sorihiko.dango.server;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reads an administrator-provided login only on the provider's IO executor. */
final class NeteaseCookie {
    private NeteaseCookie() {}

    static String load(Path directory) throws IOException {
        if (directory == null) return "";
        String json = read(directory.resolve("cookie.json"));
        if (json.isBlank()) return "";
        try {
            JsonArray cookies = JsonParser.parseString(json).getAsJsonArray();
            Map<String, String> pairs = new LinkedHashMap<>();
            for (JsonElement item : cookies) {
                JsonObject cookie = item.getAsJsonObject();
                String domain = field(cookie, "domain", "music.163.com").toLowerCase(Locale.ROOT);
                if (domain.startsWith(".")) domain = domain.substring(1);
                String path = field(cookie, "path", "/");
                if (!Set.of("music.163.com", "163.com").contains(domain) || !"/api/".startsWith(path)) continue;
                String name = field(cookie, "name", null), value = field(cookie, "value", null);
                if (name == null || value == null) throw invalidJson();
                // Parse the export fields directly. Commas and equals signs belong to the value,
                // not to a second text-format parser; preserve them exactly as exported.
                if (!name.matches("[A-Za-z0-9_!#$%&'*+.^|~-]+")
                        || value.chars().anyMatch(c -> c < 0x21 || c > 0x7e || c == ';'))
                    throw new IOException("Invalid server cookie.json; cookie names or values contain unsupported characters");
                pairs.put(name, value);
            }
            String header = String.join("; ", pairs.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            if (header.length() > 16 * 1024) throw new IOException("Server NetEase Cookie header is too large");
            return header;
        } catch (RuntimeException e) {
            // Parser exceptions may contain the input, so never attach them to public errors.
            throw invalidJson();
        }
    }

    private static String field(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }

    private static String read(Path path) throws IOException {
        if (!Files.exists(path)) return "";
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("Server NetEase Cookie file is too large");
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private static IOException invalidJson() {
        return new IOException("Invalid server cookie.json; expected an array of cookie objects");
    }
}
