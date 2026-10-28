package me.sorihiko.dango.server;

import com.sun.net.httpserver.HttpServer;
import me.sorihiko.dango.protocol.MusicProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated synthetic provider responses; no real accounts or membership claims. */
class NeteaseProviderTest {
    @TempDir Path directory;
    private HttpServer fixture;
    private NeteaseProvider provider;
    private final List<String> cookies = new CopyOnWriteArrayList<>();
    private final List<String> searchQueries = new CopyOnWriteArrayList<>();
    private volatile String playback = """
            {"code":200,"data":[{"id":101,"code":200,"fee":1,"url":"https://example.invalid/synthetic.mp3","type":"mp3","freeTrialInfo":null}]}
            """;

    @BeforeEach void start() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fixture.createContext("/api/", exchange -> {
            try {
                cookies.add(Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"), ""));
                if (exchange.getRequestURI().getPath().equals("/api/cloudsearch/pc")) searchQueries.add(exchange.getRequestURI().getRawQuery());
                String body = switch (exchange.getRequestURI().getPath()) {
                    case "/api/song/detail" -> """
                            {"code":200,"songs":[{"id":101,"name":"Synthetic licensed song","dt":180000,"ar":[{"name":"Fixture artist"}],"al":{"picUrl":""}}]}
                            """;
                    case "/api/song/enhance/player/url" -> playback;
                    case "/api/song/lyric" -> "{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]Synthetic lyrics\"}}";
                    case "/api/cloudsearch/pc" -> "{\"code\":200,\"result\":{\"songs\":[],\"songCount\":0}}";
                    default -> "{\"code\":404}";
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        fixture.start();
        URI origin = new URI("http", null, fixture.getAddress().getAddress().getHostAddress(), fixture.getAddress().getPort(), null, null, null);
        provider = new NeteaseProvider(directory, origin);
    }

    @AfterEach void stop() { if (provider != null) provider.close(); if (fixture != null) fixture.stop(0); }

    private void login(String session) throws Exception {
        Files.writeString(directory.resolve("cookie.json"), MusicProtocol.GSON.toJson(List.of(Map.of("name", "MUSIC_U", "value", session))));
    }

    @Test void chatUsesTenResultsAndClientKeepsTwentyWithCorrectOffsets() throws Exception {
        provider.search("synthetic", 1, MusicChat.SEARCH_PAGE_SIZE);
        provider.search("synthetic", 1);
        assertTrue(searchQueries.get(0).contains("limit=10&offset=10"));
        assertTrue(searchQueries.get(1).contains("limit=20&offset=20"));
    }

    @Test void serverCookieIsUsedForLookupAndLyricsButNotReturnedToPlayers() throws Exception {
        String login = "MUSIC_U=synthetic-entitled-session; __csrf=synthetic-csrf; fixture_metadata=synthetic,comma,value==";
        Files.writeString(directory.resolve("cookie.json"), """
                [
                  {"name":"MUSIC_U","value":"synthetic-entitled-session"},
                  {"name":"__csrf","value":"synthetic-csrf"},
                  {"name":"fixture_metadata","value":"synthetic,comma,value=="}
                ]
                """);
        MusicProvider.Audio audio = provider.resolve(101);
        provider.search("fixture", 0);
        assertEquals(4, cookies.size()); assertTrue(cookies.stream().allMatch(login::equals));
        assertEquals(101, audio.track().id); assertEquals("[00:01.00]Synthetic lyrics", audio.lrc());
        String output = MusicProtocol.GSON.toJson(audio);
        assertFalse(output.contains("MUSIC_U")); assertFalse(output.contains("synthetic-entitled-session"));
    }

    @Test void cookieChangesArePickedUpWithoutRecreatingProvider() throws Exception {
        login("synthetic-first"); provider.resolve(101);
        login("synthetic-second"); provider.resolve(101);
        assertEquals(List.of("MUSIC_U=synthetic-first", "MUSIC_U=synthetic-first", "MUSIC_U=synthetic-first",
                "MUSIC_U=synthetic-second", "MUSIC_U=synthetic-second", "MUSIC_U=synthetic-second"), cookies);
    }

    @Test void absentCookieKeepsAnonymousPlaybackWorking() throws Exception {
        assertNotNull(provider.resolve(101)); assertTrue(cookies.stream().allMatch(String::isEmpty));
    }

    @Test void previewIsRejectedEvenWithConfiguredLogin() throws Exception {
        login("synthetic-account");
        playback = playback.replace("\"freeTrialInfo\":null", "\"freeTrialInfo\":{\"start\":0,\"end\":30}");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> provider.resolve(101));
        assertTrue(error.getMessage().contains("only a preview"));
    }

    @Test void rejectedLoginAndMissingEntitlementHaveUsefulMessages() throws Exception {
        login("synthetic-account");
        playback = "{\"code\":301}";
        assertTrue(assertThrows(IllegalStateException.class, () -> provider.resolve(101)).getMessage().contains("login expired or was rejected"));
        playback = "{\"code\":200,\"data\":[{\"id\":101,\"code\":-110,\"url\":null,\"type\":null}]}";
        assertTrue(assertThrows(IllegalStateException.class, () -> provider.resolve(101)).getMessage().contains("check login, membership"));
    }

    @Test void unsupportedAudioFormatStillFailsClearly() throws Exception {
        playback = playback.replace("\"type\":\"mp3\"", "\"type\":\"flac\"");
        assertTrue(assertThrows(IllegalStateException.class, () -> provider.resolve(101)).getMessage().contains("complete MP3"));
    }
}
