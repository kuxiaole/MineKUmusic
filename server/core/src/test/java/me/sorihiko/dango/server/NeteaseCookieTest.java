package me.sorihiko.dango.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseCookieTest {
    @TempDir Path directory;

    @Test void startupCreatesAnonymousTemplateAndPreservesConfiguredLogin() throws Exception {
        ServerConfig.load(directory);
        Path file = directory.resolve("netease-cookie.txt");
        assertTrue(Files.exists(file)); assertEquals("", NeteaseCookie.load(directory));
        Files.writeString(file, "MUSIC_U=synthetic-account-session");
        ServerConfig.load(directory);
        assertEquals("MUSIC_U=synthetic-account-session", NeteaseCookie.load(directory));
    }

    @Test void acceptsBomCommentsAndRequestHeaderPrefix() throws Exception {
        Files.writeString(directory.resolve("netease-cookie.txt"), "\uFEFF# Synthetic cookie fixture\nCookie: MUSIC_U=synthetic-session==; __csrf=synthetic-csrf;\n");
        assertEquals("MUSIC_U=synthetic-session==; __csrf=synthetic-csrf", NeteaseCookie.load(directory));
    }

    @Test void importsAllMusicCookieJson() throws Exception {
        ServerConfig.load(directory);
        Files.writeString(directory.resolve("cookie.json"), """
                [
                  {"domain":".music.163.com","hostOnly":false,"httpOnly":true,"name":"MUSIC_U","path":"/","value":"synthetic-json-session"},
                  {"domain":"music.163.com","name":"__csrf","path":"/","value":"synthetic-csrf"}
                ]
                """);
        assertEquals("MUSIC_U=synthetic-json-session; __csrf=synthetic-csrf", NeteaseCookie.load(directory));
    }

    @Test void textTakesPriorityAndEditsApplyOnNextRead() throws Exception {
        Files.writeString(directory.resolve("cookie.json"), "[{\"name\":\"MUSIC_U\",\"value\":\"synthetic-json-session\"}]");
        Path file = directory.resolve("netease-cookie.txt");
        Files.writeString(file, "MUSIC_U=synthetic-first-session");
        assertEquals("MUSIC_U=synthetic-first-session", NeteaseCookie.load(directory));
        Files.writeString(file, "MUSIC_U=synthetic-second-session");
        assertEquals("MUSIC_U=synthetic-second-session", NeteaseCookie.load(directory));
        Files.writeString(file, "# Empty text uses the JSON fixture\n");
        assertEquals("MUSIC_U=synthetic-json-session", NeteaseCookie.load(directory));
    }
}
