package me.sorihiko.dango.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir Path plugins;

    @Test void freshInstallationUsesTheNewDirectoryAndBrand() throws Exception {
        Path preferred = plugins.resolve("MineKUmusic");
        assertEquals(preferred, ServerConfig.dataDirectory(preferred, "minekumusic", "DangoMusic"));
        assertEquals("MineKU Music", ServerConfig.load(preferred).channelName());
    }

    @Test void existingFoliaAndVelocityConfigsKeepTheirServerIdAndFiles() throws Exception {
        for (String legacyName : new String[]{"DangoMusic", "dangomusic-server"}) {
            Path preferred = plugins.resolve("MineKUmusic");
            Path legacy = Files.createDirectories(plugins.resolve(legacyName));
            Files.writeString(legacy.resolve("config.properties"), "server-id=synthetic-network\nchannel-name=Existing channel\n");
            Files.writeString(legacy.resolve("queue.json"), "{\"queue\":[]}");
            Path selected = ServerConfig.dataDirectory(preferred, legacyName);
            assertEquals(legacy, selected);
            ServerConfig loaded = ServerConfig.load(selected);
            assertEquals("synthetic-network", loaded.serverId());
            assertEquals("Existing channel", loaded.channelName());
            assertEquals("{\"queue\":[]}", Files.readString(selected.resolve("queue.json")));
            assertFalse(Files.exists(preferred));
        }
    }

    @Test void lowercaseBrandDirectoryKeepsItsDataBeforeTheOriginalDirectory() throws Exception {
        Path preferred = plugins.resolve("MineKUmusic");
        Path lowercase = Files.createDirectories(plugins.resolve("minekumusic"));
        Path original = Files.createDirectories(plugins.resolve("DangoMusic"));
        Files.writeString(lowercase.resolve("config.properties"), "server-id=synthetic-lowercase\nchannel-name=Existing channel\n");
        Files.writeString(lowercase.resolve("queue.json"), "{\"queue\":[]}");
        Files.writeString(original.resolve("config.properties"), "server-id=synthetic-original\n");
        Path selected = ServerConfig.dataDirectory(preferred, "minekumusic", "DangoMusic");
        assertTrue(Files.isSameFile(lowercase, selected));
        ServerConfig loaded = ServerConfig.load(selected);
        assertEquals("synthetic-lowercase", loaded.serverId());
        assertEquals("Existing channel", loaded.channelName());
        assertEquals("{\"queue\":[]}", Files.readString(selected.resolve("queue.json")));
        assertEquals("server-id=synthetic-original\n", Files.readString(original.resolve("config.properties")));
    }

    @Test void explicitNewConfigTakesPrecedenceWithoutChangingLegacyFiles() throws Exception {
        Path preferred = Files.createDirectories(plugins.resolve("MineKUmusic"));
        Path legacy = Files.createDirectories(plugins.resolve("DangoMusic"));
        Files.writeString(preferred.resolve("config.properties"), "server-id=synthetic-new\n");
        Files.writeString(legacy.resolve("config.properties"), "server-id=synthetic-old\n");
        assertEquals(preferred, ServerConfig.dataDirectory(preferred, "minekumusic", "DangoMusic"));
        assertEquals("synthetic-new", ServerConfig.load(preferred).serverId());
        assertEquals("server-id=synthetic-old\n", Files.readString(legacy.resolve("config.properties")));
    }
}
