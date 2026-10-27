package me.sorihiko.dango.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir Path plugins;

    @Test void chargingDefaultsToDisabledWithKuCurrency() throws Exception {
        ServerConfig config = ServerConfig.load(plugins);
        assertFalse(config.requestFeeEnabled()); assertEquals(BigDecimal.ZERO, config.requestPrice());
        assertEquals("KU\u5e01", config.currencyName()); assertFalse(config.economyBridgeOnly());
    }
    @Test void priceCurrencyAndBackendModeCanBeConfigured() throws Exception {
        Files.writeString(plugins.resolve("config.properties"), "request-fee-enabled=true\nrequest-fee-amount=2.50\ncurrency-name=Fixture credits\neconomy-bridge-only=true\neconomy-bridge-secret=synthetic-configuration-secret-for-tests\n");
        ServerConfig config = ServerConfig.load(plugins);
        assertEquals(new BigDecimal("2.5"), config.requestPrice()); assertEquals("Fixture credits", config.currencyName());
        assertTrue(config.economyBridgeOnly());
    }
    @Test void priceRequiresNonNegativeAmountsWithAtMostTwoDecimalPlaces() throws Exception {
        for (String amount : new String[]{"-1", "0.001", "1000001"}) {
            Files.writeString(plugins.resolve("config.properties"), "request-fee-amount=" + amount + "\n");
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(plugins));
        }
    }

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
