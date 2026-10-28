package me.sorihiko.dango.server;

import com.google.gson.JsonParser;
import me.sorihiko.dango.music.CookieValues;
import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.protocol.MusicProtocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated synthetic exports and queues; no real account or provider requests. */
class MultiSourceTest {
    @TempDir Path directory;
    @Test void oldExportIsCopiedOnceWithoutReplacingNewAccount() throws Exception {
        String legacy = "[{\"name\":\"MUSIC_U\",\"value\":\"synthetic-old\"}]";
        Files.writeString(directory.resolve("cookie.json"), legacy);
        ServerConfig.load(directory);
        assertEquals(legacy, Files.readString(directory.resolve("cookie.json")));
        assertEquals(legacy, Files.readString(directory.resolve("netease-cookie.json")));
        assertEquals("[]", Files.readString(directory.resolve("qq-cookie.json")).trim());
        Files.writeString(directory.resolve("netease-cookie.json"), "[{\"name\":\"MUSIC_U\",\"value\":\"synthetic-new\"}]");
        ServerConfig.load(directory);
        assertEquals("MUSIC_U=synthetic-new", NeteaseCookie.load(directory));
        assertEquals(legacy, Files.readString(directory.resolve("cookie.json")));
    }
    @Test void qqBrowserArrayRetainsSessionAndSeparatesAccounts() throws Exception {
        Files.writeString(directory.resolve("qq-cookie.json"), """
                [{"domain":".qq.com","path":"/","name":"qqmusic_uin","value":"123456"},
                 {"domain":".qq.com","path":"/","name":"qqmusic_key","value":"synthetic=key,part"},
                 {"domain":".qq.com","path":"/","name":"login_type","value":"1"}]
                """);
        var account = ChannelMusicProvider.qqAccount(directory);
        assertEquals("123456", account.getUserId());
        assertEquals("synthetic=key,part", CookieValues.cookie(account.getCookies(), "qqmusic_key"));
        ServerCookies.save(directory, "qq", account.getCookies());
        assertTrue(JsonParser.parseString(Files.readString(directory.resolve("qq-cookie.json"))).isJsonArray());
        assertEquals("", NeteaseCookie.load(directory));
        assertEquals(account.getUserId(), ChannelMusicProvider.qqAccount(directory).getUserId());
    }
    @Test void economyBridgeDoesNotCreateMusicAccountFiles() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "economy-bridge-only=true\neconomy-bridge-secret=synthetic-bridge-key-long-enough-for-tests\n");
        assertTrue(ServerConfig.load(directory).economyBridgeOnly());
        assertFalse(Files.exists(directory.resolve("netease-cookie.json")));
        assertFalse(Files.exists(directory.resolve("qq-cookie.json")));
    }
    @Test void mixedQueueUsesNativeIdentityAndRestoresPlaybackPosition() {
        AtomicLong time = new AtomicLong();
        QueueEngine engine = new QueueEngine(time::get, 3, 100, 0); engine.subscribe("fixture");
        Track netease = new Track(123, "Synthetic NetEase", List.of("Fixture"), 180000, "");
        Track qq = new Track(new SongRef("qq", "123"), "Synthetic QQ", List.of("Fixture"), 180000, "");
        engine.add("fixture", "Fixture", netease); engine.add("fixture", "Fixture", qq);
        assertEquals("netease:123", engine.selectNext().track.key()); engine.ready();
        time.set(12000_000_000L);
        QueueEngine restored = new QueueEngine(time::get, 3, 100, 0); restored.restore(engine.save());
        assertEquals(12000, restored.position());
        assertEquals("qq:123", restored.queue().getFirst().track.key());
        assertEquals(0, restored.listeners()); restored.skip(); assertEquals("qq:123", restored.selectNext().track.key());
    }
    @Test void missingSourceFromOldQueueMeansNetease() {
        Track old = MusicProtocol.GSON.fromJson("{\"id\":123,\"name\":\"Synthetic legacy\"}", Track.class);
        assertEquals(new SongRef("netease", "123"), old.ref());
        assertEquals(123, old.ref().legacyId());
    }
}
