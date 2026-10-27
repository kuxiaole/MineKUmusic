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
        String text = read(directory.resolve("netease-cookie.txt"));
        String header = String.join("; ", text.lines().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList());
        if (header.regionMatches(true, 0, "Cookie:", 0, 7)) header = header.substring(7).strip();
        if (!header.isBlank()) return normalize(header);

        String json = read(directory.resolve("cookie.json"));
        if (json.isBlank()) return "";
        try {
            JsonArray cookies = JsonParser.parseString(json).getAsJsonArray();
            List<String> pairs = new ArrayList<>();
            for (JsonElement item : cookies) {
                JsonObject cookie = item.getAsJsonObject();
                String domain = field(cookie, "domain", "music.163.com").toLowerCase(Locale.ROOT);
                if (domain.startsWith(".")) domain = domain.substring(1);
                String path = field(cookie, "path", "/");
                if (!Set.of("music.163.com", "163.com").contains(domain) || !"/api/".startsWith(path)) continue;
                pairs.add(field(cookie, "name", "") + "=" + field(cookie, "value", ""));
            }
            return normalize(String.join("; ", pairs));
        } catch (RuntimeException e) {
            // Parser exceptions may contain the input, so never attach them to public errors.
            throw new IOException("Invalid server cookie.json; expected an array of cookie objects");
        }
    }

    private static String field(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
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

    private static String normalize(String header) throws IOException {
        if (header.length() > 16 * 1024) throw new IOException("Server NetEase Cookie header is too large");
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String raw : header.split(";")) {
            String pair = raw.strip();
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            if (separator <= 0) throw invalid();
            String name = pair.substring(0, separator).strip(), value = pair.substring(separator + 1).strip();
            if (!name.matches("[A-Za-z0-9_!#$%&'*+.^|~-]+") || value.chars().anyMatch(c -> c < 0x21 || c > 0x7e || c == ','))
                throw invalid();
            pairs.put(name, value);
        }
        return String.join("; ", pairs.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    private static IOException invalid() {
        return new IOException("Invalid server NetEase Cookie; use name=value pairs separated by semicolons");
    }
}
