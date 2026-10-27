package me.sorihiko.dango.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public record ServerConfig(String serverId, String channelName, int playerLimit, int queueLimit, int cooldownSeconds, int syncSeconds) {
    public static Path dataDirectory(Path preferred, String... legacyNames) {
        if (Files.isRegularFile(preferred.resolve("config.properties"))) return preferred;
        for (String legacyName : legacyNames) {
            Path legacy = preferred.resolveSibling(legacyName);
            if (Files.isRegularFile(legacy.resolve("config.properties"))) return legacy;
        }
        return preferred;
    }

    public static ServerConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path cookie = directory.resolve("netease-cookie.txt");
        if (!Files.exists(cookie)) {
            try (InputStream template = ServerConfig.class.getResourceAsStream("/netease-cookie.txt.example")) {
                Files.copy(Objects.requireNonNull(template), cookie);
            }
        }
        Path path = directory.resolve("config.properties");
        Properties p = new Properties();
        if (Files.exists(path)) try (Reader r = Files.newBufferedReader(path)) { p.load(r); }
        p.putIfAbsent("server-id", UUID.randomUUID().toString());
        p.putIfAbsent("channel-name", "MineKU Music");
        p.putIfAbsent("player-queue-limit", "3"); p.putIfAbsent("queue-limit", "100");
        p.putIfAbsent("request-cooldown-seconds", "30"); p.putIfAbsent("sync-seconds", "2");
        try (Writer w = Files.newBufferedWriter(path)) { p.store(w, "Keep server-id unchanged to preserve listener preferences."); }
        return new ServerConfig(p.getProperty("server-id"), p.getProperty("channel-name"),
                integer(p, "player-queue-limit", 1, 100), integer(p, "queue-limit", 1, 1000),
                integer(p, "request-cooldown-seconds", 0, 3600), integer(p, "sync-seconds", 1, 10));
    }
    private static int integer(Properties p, String key, int min, int max) {
        int value = Integer.parseInt(p.getProperty(key));
        if (value < min || value > max) throw new IllegalArgumentException("Invalid config: " + key);
        return value;
    }
}
