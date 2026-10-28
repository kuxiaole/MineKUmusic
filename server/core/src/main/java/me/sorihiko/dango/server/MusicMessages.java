package me.sorihiko.dango.server;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Immutable templates/theme. Dynamic values never become MiniMessage markup or commands. */
public final class MusicMessages {
    private static final Pattern VARIABLE = Pattern.compile("\\{([a-z_]+)\\}");
    private static final Pattern TAG = Pattern.compile("(?<!\\\\)<(/?)([^<>]+)>");
    private static final String TEMPLATE = template();
    private static final Properties DEFAULT_VALUES = properties(TEMPLATE);
    private static final Map<String, String> SOURCES = sources();
    private static final MusicMessages DEFAULT = new MusicMessages(DEFAULT_VALUES);
    private final Map<String, String> values;
    private final MiniMessage parser;
    private final TagResolver colors;

    private MusicMessages(Properties properties) {
        Map<String, String> copy = new HashMap<>();
        properties.forEach((key, value) -> copy.put((String) key, (String) value));
        values = Map.copyOf(copy);
        TagResolver.Builder palette = TagResolver.builder();
        for (String key : DEFAULT_VALUES.stringPropertyNames()) if (key.startsWith("color.")) {
            String value = values.get(key).strip();
            TextColor color = value.matches("#[0-9a-fA-F]{6}") ? TextColor.fromHexString(value) : NamedTextColor.NAMES.value(value);
            if (color == null) throw new IllegalArgumentException("messages.properties: " + key + " 需要 Minecraft 颜色名或 #RRGGBB");
            palette.tag(key.substring(6), Tag.styling(color));
        }
        colors = palette.build();
        parser = MiniMessage.builder().strict(true).tags(TagResolver.builder()
                .resolver(StandardTags.color()).resolver(StandardTags.decorations()).resolver(StandardTags.newline()).build()).build();
        for (String key : DEFAULT_VALUES.stringPropertyNames()) {
            if (key.startsWith("color.") || key.endsWith(".arguments")) continue;
            Set<String> allowed = variables(DEFAULT_VALUES.getProperty(key));
            Set<String> actual = variables(values.get(key));
            if (!allowed.containsAll(actual)) throw new IllegalArgumentException("messages.properties: " + key + " 使用了未知占位符；可用：" + allowed);
            Object[] samples = allowed.stream().flatMap(name -> java.util.stream.Stream.of(name, "sample")).toArray();
            try {
                // Reject misspelled/unavailable tags instead of silently displaying broken formatting.
                Matcher tags = TAG.matcher(values.get(key));
                while (tags.find()) {
                    String name = tags.group(2).toLowerCase(Locale.ROOT);
                    if (!colors.has(name) && !name.matches("#[0-9a-f]{6}") && NamedTextColor.NAMES.value(name) == null
                            && !Set.of("bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st", "obfuscated", "obf", "newline", "br").contains(name))
                        throw new IllegalArgumentException("不支持的格式标签 <" + name + ">");
                }
                render(key, samples);
            } catch (RuntimeException e) { throw new IllegalArgumentException("messages.properties: " + key + " 格式错误：" + e.getMessage(), e); }
        }
    }
    public static MusicMessages defaults() { return DEFAULT; }
    public static MusicMessages load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("messages.properties");
        Properties values = new Properties();
        if (Files.exists(path)) try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(reader); }
        boolean missing = !values.keySet().containsAll(DEFAULT_VALUES.keySet());
        DEFAULT_VALUES.forEach(values::putIfAbsent);
        MusicMessages result = new MusicMessages(values);
        // Leave user comments and layout untouched on ordinary reloads; upgrades add documented defaults.
        if (missing) ServerConfig.writeAnnotated(path, values, TEMPLATE);
        return result;
    }
    public String value(String key) { return Objects.requireNonNull(values.get(key), "Unknown language key: " + key); }
    public Component render(String key, Object... args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected name/value pairs");
        Map<String, Object> replacements = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) replacements.put((String) args[i], args[i + 1]);
        TagResolver.Builder resolver = TagResolver.builder().resolver(colors);
        String input = value(key);
        for (String name : variables(input)) {
            Object value = Objects.requireNonNull(replacements.get(name), "Missing placeholder " + key + ": " + name);
            resolver.resolver(Placeholder.component("arg_" + name, value instanceof Component c ? c : Component.text(String.valueOf(value))));
        }
        return parser.deserialize(VARIABLE.matcher(input).replaceAll("<arg_$1>"), resolver.build());
    }
    public String text(String key, Object... args) { return MusicChat.plain(render(key, args)); }
    public Component translated(String original, boolean ok) {
        String key = SOURCES.get(original);
        return key == null ? render(ok ? "notice.text" : "error.text", "text", original) : render(key);
    }
    public Failure failure(String key, Object... args) { return new Failure(key, args, text(key, args)); }
    public static final class Failure extends IllegalArgumentException {
        public final String key;
        private final Object[] args;
        private Failure(String key, Object[] args, String text) { super(text); this.key = key; this.args = args.clone(); }
        public Component render(MusicMessages messages) { return messages.render(key, args); }
    }
    private static Set<String> variables(String input) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(input);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }
    private static String template() {
        try (InputStream stream = MusicMessages.class.getResourceAsStream("/messages.properties.example")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static Properties properties(String text) {
        try { Properties result = new Properties(); result.load(new StringReader(text)); return result; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static Map<String, String> sources() {
        try (InputStream stream = MusicMessages.class.getResourceAsStream("/message-sources.properties")) {
            Map<String, String> result = new HashMap<>();
            Properties sources = properties(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
            sources.forEach((key, value) -> result.put((String) value, (String) key));
            return Map.copyOf(result);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
