package me.sorihiko.dango.server;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

public record ServerConfig(String serverId, String channelName, int playerLimit, int queueLimit, int cooldownSeconds, int syncSeconds,
                           boolean requestFeeEnabled, BigDecimal requestFeeAmount, String currencyName,
                           boolean economyBridgeOnly, String economyBridgeSecret) {
    public ServerConfig(String serverId, String channelName, int playerLimit, int queueLimit, int cooldownSeconds, int syncSeconds) {
        this(serverId, channelName, playerLimit, queueLimit, cooldownSeconds, syncSeconds, false, new BigDecimal("10"), "KU\u5e01", false, "");
    }
    public BigDecimal requestPrice() { return requestFeeEnabled ? requestFeeAmount : BigDecimal.ZERO; }
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
        p.putIfAbsent("request-fee-enabled", "false"); p.putIfAbsent("request-fee-amount", "10");
        p.putIfAbsent("currency-name", "KU\u5e01"); p.putIfAbsent("economy-bridge-only", "false");
        p.putIfAbsent("economy-bridge-secret", "");
        BigDecimal amount = new BigDecimal(p.getProperty("request-fee-amount")).stripTrailingZeros();
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000")) > 0 || amount.scale() > 2)
            throw new IllegalArgumentException("request-fee-amount must be between 0 and 1000000, with at most two decimal places");
        String currency = p.getProperty("currency-name").strip();
        if (currency.isEmpty() || currency.length() > 32 || currency.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("currency-name must contain 1 to 32 visible characters");
        try (Writer w = Files.newBufferedWriter(path)) { p.store(w, "Keep server-id unchanged to preserve listener preferences."); }
        return new ServerConfig(p.getProperty("server-id"), p.getProperty("channel-name"),
                integer(p, "player-queue-limit", 1, 100), integer(p, "queue-limit", 1, 1000),
                integer(p, "request-cooldown-seconds", 0, 3600), integer(p, "sync-seconds", 1, 10),
                bool(p, "request-fee-enabled"), amount, currency, bool(p, "economy-bridge-only"), p.getProperty("economy-bridge-secret").strip());
    }
    private static boolean bool(Properties p, String key) {
        String value = p.getProperty(key);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Invalid config: " + key);
        return Boolean.parseBoolean(value);
    }
    private static int integer(Properties p, String key, int min, int max) {
        int value = Integer.parseInt(p.getProperty(key));
        if (value < min || value > max) throw new IllegalArgumentException("Invalid config: " + key);
        return value;
    }
}
