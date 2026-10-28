package me.sorihiko.dango.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cookie export compatibility using isolated synthetic values only. */
class NeteaseCookieTest {
    @TempDir Path directory;

    @Test void startupCreatesEmptyJsonAndPreservesTheOriginalExport() throws Exception {
        ServerConfig.load(directory);
        Path file = directory.resolve("cookie.json");
        assertEquals("[]", Files.readString(file).strip());
        assertFalse(Files.exists(directory.resolve("netease-cookie.txt")));
        assertEquals("", NeteaseCookie.load(directory));
        String exported = "[{\"name\":\"MUSIC_U\",\"value\":\"synthetic-session\"}]";
        Files.writeString(file, exported);
        ServerConfig.load(directory);
        assertEquals("MUSIC_U=synthetic-session", NeteaseCookie.load(directory));
        assertEquals(exported, Files.readString(file));
    }

    @Test void acceptsBomAndPreservesCommasEqualsSignsAndEmptyValues() throws Exception {
        Files.writeString(directory.resolve("cookie.json"), "\uFEFF" + """
                [
                  {"name":"MUSIC_U","value":"synthetic-session=="},
                  {"name":"fixture_metadata","value":"synthetic,comma,value=="},
                  {"name":"fixture_empty","value":""}
                ]
                """);
        assertEquals("MUSIC_U=synthetic-session==; fixture_metadata=synthetic,comma,value==; fixture_empty=", NeteaseCookie.load(directory));
    }

    @Test void importsAllMusicAndBrowserExportFields() throws Exception {
        ServerConfig.load(directory);
        Files.writeString(directory.resolve("cookie.json"), """
                [
                  {"domain":".music.163.com","expirationDate":2000000000,"hostOnly":false,"httpOnly":true,
                   "name":"MUSIC_U","path":"/","sameSite":"no_restriction","secure":true,"session":false,
                   "storeId":"0","value":"synthetic-json-session"},
                  {"domain":"music.163.com","name":"__csrf","path":"/","value":"synthetic-csrf"}
                ]
                """);
        assertEquals("MUSIC_U=synthetic-json-session; __csrf=synthetic-csrf", NeteaseCookie.load(directory));
    }

    @Test void textIsIgnoredAndJsonEditsApplyOnNextRead() throws Exception {
        Path legacy = directory.resolve("netease-cookie.txt");
        Files.writeString(legacy, "MUSIC_U=synthetic-legacy-session");
        assertEquals("", NeteaseCookie.load(directory));
        Path file = directory.resolve("cookie.json");
        Files.writeString(file, "[{\"name\":\"MUSIC_U\",\"value\":\"synthetic-first-session\"}]");
        assertEquals("MUSIC_U=synthetic-first-session", NeteaseCookie.load(directory));
        Files.writeString(file, "[{\"name\":\"MUSIC_U\",\"value\":\"synthetic-second-session\"}]");
        assertEquals("MUSIC_U=synthetic-second-session", NeteaseCookie.load(directory));
        Files.writeString(file, "[]");
        assertEquals("", NeteaseCookie.load(directory));
        assertEquals("MUSIC_U=synthetic-legacy-session", Files.readString(legacy));
    }

    @Test void otherFormatsReportJsonInstructions() throws Exception {
        for (String value : new String[]{"MUSIC_U=synthetic-text", "{\"MUSIC_U\":\"synthetic-object\"}", "[\"synthetic-string\"]"}) {
            Files.writeString(directory.resolve("cookie.json"), value);
            IOException error = assertThrows(IOException.class, () -> NeteaseCookie.load(directory));
            assertEquals("Invalid server cookie.json; expected an array of cookie objects", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test void nameAndValueMustBeStrings() throws Exception {
        for (String value : new String[]{"[{\"name\":\"MUSIC_U\"}]", "[{\"name\":\"MUSIC_U\",\"value\":123}]", "[{\"value\":\"synthetic-session\"}]"}) {
            Files.writeString(directory.resolve("cookie.json"), value);
            IOException error = assertThrows(IOException.class, () -> NeteaseCookie.load(directory));
            assertEquals("Invalid server cookie.json; expected an array of cookie objects", error.getMessage());
        }
    }
}
