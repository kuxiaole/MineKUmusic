package me.sorihiko.dango.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static me.sorihiko.dango.server.MusicCommands.*;
import static org.junit.jupiter.api.Assertions.*;

class MusicCommandsTest {
    private final MusicServer.Peer listener = new MusicServer.Peer(UUID.randomUUID(), "Synthetic listener", true, true, true, false);
    private final MusicServer.Peer admin = MusicServer.console();

    @Test void completionUsesPermissionsPrefixesAndArgumentPosition() {
        assertEquals(List.of("request"), suggest(listener, Mode.FOLIA, new String[]{"RE"}, Snapshot.EMPTY));
        assertEquals(List.of("request", "resume", "remove", "reload"), suggest(
                new MusicServer.Peer(UUID.randomUUID(), "Synthetic admin", true, true, true, true), Mode.FOLIA, new String[]{"re"}, Snapshot.EMPTY));
        assertEquals(List.of("reload"), suggest(admin, Mode.VELOCITY, new String[]{"help", "rel"}, Snapshot.EMPTY));
        assertTrue(suggest(listener, Mode.FOLIA, new String[]{"search", ""}, Snapshot.EMPTY).isEmpty());
        assertTrue(suggest(listener, Mode.FOLIA, new String[]{"queue", "1", ""}, Snapshot.EMPTY).isEmpty());
    }
    @Test void queueSuggestionsUseOwnEntriesAndActualPageCount() {
        List<QueueItem> items = IntStream.range(0, 41).mapToObj(i -> new QueueItem("synthetic-entry-" + i,
                i < 2 ? listener.id().toString() : "another-synthetic-listener")).toList();
        Snapshot state = new Snapshot(items, "synthetic-playing-instance");
        assertEquals(List.of("1", "2", "3"), suggest(listener, Mode.FOLIA, new String[]{"queue", ""}, state));
        assertEquals(List.of("synthetic-entry-0", "synthetic-entry-1"), suggest(listener, Mode.FOLIA, new String[]{"withdraw", ""}, state));
        assertEquals(List.of("synthetic-entry-40"), suggest(admin, Mode.FOLIA, new String[]{"remove", "synthetic-entry-40"}, state));
        assertEquals(List.of("synthetic-playing-instance"), suggest(listener, Mode.FOLIA, new String[]{"vote", ""}, state));
    }
    @Test void bridgeAndProxyExposeTheirRespectiveCommands() {
        assertEquals(List.of("help", "reload", "payment"), suggest(admin, Mode.BRIDGE, new String[]{""}, Snapshot.EMPTY));
        assertEquals(List.of("paid"), suggest(admin, Mode.BRIDGE, new String[]{"payment", "synthetic-id", "p"}, Snapshot.EMPTY));
        assertFalse(suggest(admin, Mode.VELOCITY, new String[]{""}, Snapshot.EMPTY).contains("payment"));
        assertTrue(help(admin, Mode.BRIDGE, "").stream().anyMatch(line -> line.contains("经济桥接")));
    }
    @Test void helpExplainsReloadAndFiltersCommandsForTheCaller() {
        List<String> help = help(listener, Mode.FOLIA, "");
        assertTrue(help.stream().anyMatch(line -> line.startsWith("/minekumusic request") && line.contains("确认")));
        assertFalse(help.stream().anyMatch(line -> line.startsWith("/minekumusic reload")));
        assertTrue(help(admin, Mode.FOLIA, "RELOAD").stream().anyMatch(line -> line.contains("变更需重启")));
        assertEquals("help", name(new String[0]));
    }
}
