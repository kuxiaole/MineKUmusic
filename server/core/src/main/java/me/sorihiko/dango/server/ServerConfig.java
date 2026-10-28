package me.sorihiko.dango.server;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
        return load(directory, null);
    }
    public static ServerConfig load(Path directory, ServerConfig running) throws IOException {
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
        String template;
        try (InputStream input = ServerConfig.class.getResourceAsStream("/config.properties.example")) {
            template = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        Properties defaults = new Properties();
        defaults.load(new StringReader(template));
        defaults.forEach(p::putIfAbsent);
        if (p.getProperty("server-id").isBlank()) p.setProperty("server-id", UUID.randomUUID().toString());
        BigDecimal amount = new BigDecimal(p.getProperty("request-fee-amount")).stripTrailingZeros();
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000")) > 0 || amount.scale() > 2)
            throw new IllegalArgumentException("request-fee-amount must be between 0 and 1000000, with at most two decimal places");
        String currency = p.getProperty("currency-name").strip();
        if (currency.isEmpty() || currency.length() > 32 || currency.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("currency-name must contain 1 to 32 visible characters");
        ServerConfig config = new ServerConfig(p.getProperty("server-id"), p.getProperty("channel-name"),
                integer(p, "player-queue-limit", 1, 100), integer(p, "queue-limit", 1, 1000),
                integer(p, "request-cooldown-seconds", 0, 3600), integer(p, "sync-seconds", 1, 10),
                bool(p, "request-fee-enabled"), amount, currency, bool(p, "economy-bridge-only"), p.getProperty("economy-bridge-secret").strip());
        if (running != null) {
            List<String> restart = new ArrayList<>();
            if (!config.serverId.equals(running.serverId)) restart.add("server-id");
            if (config.economyBridgeOnly != running.economyBridgeOnly) restart.add("economy-bridge-only");
            if (!config.economyBridgeSecret.equals(running.economyBridgeSecret)) restart.add("economy-bridge-secret");
            if (!restart.isEmpty()) throw new IllegalArgumentException("以下配置变更需要完整重启，本次未应用：" + String.join("、", restart));
        }
        writeAnnotated(path, p, template);
        return config;
    }
    static void writeAnnotated(Path path, Properties values, String template) throws IOException {
        StringWriter serialized = new StringWriter();
        values.store(serialized, null);
        List<String> remaining = new ArrayList<>(serialized.toString().lines().filter(line -> !line.startsWith("#")).toList());
        StringBuilder output = new StringBuilder();
        for (String line : template.lines().toList()) {
            if (!line.isBlank() && !line.startsWith("#")) {
                String prefix = line.substring(0, line.indexOf('=') + 1);
                String saved = remaining.stream().filter(value -> value.startsWith(prefix)).findFirst().orElseThrow();
                output.append(saved).append('\n');
                remaining.remove(saved);
            } else output.append(line).append('\n');
        }
        if (!remaining.isEmpty()) {
            output.append("\n# 以下为原配置中的额外选项，保留原值；本版本未定义其用途。\n");
            remaining.stream().sorted().forEach(line -> output.append(line).append('\n'));
        }
        StateFiles.write(path, output.toString());
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
