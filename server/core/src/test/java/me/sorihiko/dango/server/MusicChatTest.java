package me.sorihiko.dango.server;

import me.sorihiko.dango.protocol.MusicProtocol.Track;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.*;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory chat recipients and synthetic search results; no live server or music account. */
class MusicChatTest {
    @TempDir Path directory;
    private record Message(UUID player, List<Component> lines) {}
    private record SearchCall(String query, int page, int size) {}
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final List<SearchCall> searches = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failNext = new AtomicBoolean();
    private final MusicServer.Peer player = new MusicServer.Peer(UUID.randomUUID(), "Synthetic chat listener", true, true, true, false);
    private MusicServer server;
    private static Stream<Component> parts(Component component) {
        return Stream.concat(Stream.of(component), component.children().stream().flatMap(MusicChatTest::parts));
    }
    private static List<ClickEvent<?>> clicks(List<Component> lines) {
        return lines.stream().flatMap(MusicChatTest::parts).map(Component::clickEvent).filter(Objects::nonNull).toList();
    }
    private static String command(ClickEvent<?> event) { return ((ClickEvent.Payload.Text) event.payload()).value(); }
    private static Component button(List<Component> lines, String label) {
        return lines.stream().flatMap(MusicChatTest::parts).filter(c -> MusicChat.plain(c).equals("[" + label + "]")).findFirst().orElseThrow();
    }
    private static List<String> plain(List<Component> lines) { return lines.stream().map(MusicChat::plain).toList(); }
    private void start() throws Exception {
        var provider = new MusicProvider() {
            @Override public Search search(String query, int page, int pageSize) {
                searches.add(new SearchCall(query, page, pageSize));
                if (failNext.compareAndSet(true, false)) throw new IllegalStateException("Synthetic temporary failure");
                if (query.equals("empty")) return new Search(List.of(), false);
                List<Track> tracks = IntStream.range(page * pageSize, Math.min(23, (page + 1) * pageSize))
                        .mapToObj(i -> new Track(101 + i, query + " synthetic song " + i, List.of("Fixture artist"), 180000, "")).toList();
                return new Search(tracks, (page + 1) * pageSize < 23);
            }
            @Override public Audio resolve(long id) { throw new AssertionError("Searching must not resolve or queue audio"); }
        };
        var endpoint = new MusicServer.Endpoint() {
            @Override public void send(UUID id, List<byte[]> frames) {}
            @Override public void tell(UUID id, String text) { notices.add(text); }
            @Override public void chat(UUID id, List<Component> lines) { messages.add(new Message(id, List.copyOf(lines))); }
            @Override public void votePrompt(UUID id, String text, String command) {}
            @Override public void log(String message, Throwable error) { errors.add(error); }
        };
        server = new MusicServer(directory, new ServerConfig("synthetic-chat", "Fixture", 3, 100, 0, 1), provider, endpoint);
    }
    @AfterEach void close() throws Exception {
        if (server != null) { server.close(); server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Expected synthetic chat output");
    }
    private List<Component> search(String query) throws Exception {
        int before = messages.size(); server.command(player, new String[]{"search", query});
        await(() -> messages.size() > before); return messages.getLast().lines;
    }
    private List<Component> clickPage(List<Component> lines, String label) throws Exception {
        ClickEvent<?> click = button(lines, label).clickEvent(); assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        int before = messages.size();
        server.command(player, command(click).substring("/minekumusic ".length()).split(" "));
        await(() -> messages.size() > before); return messages.getLast().lines;
    }
    @Test void helpHasOneColoredHeadingAndCorrectClickActions() {
        List<Component> help = MusicCommands.help(player, MusicCommands.Mode.FOLIA, "");
        assertEquals(1, plain(help).stream().filter(s -> s.contains("MineKU Music")).count());
        assertTrue(plain(help).stream().noneMatch(s -> s.contains("[MineKUmusic]")));
        assertTrue(help.stream().flatMap(MusicChatTest::parts).anyMatch(c -> LIGHT_PURPLE.equals(c.color())));
        assertTrue(help.stream().flatMap(MusicChatTest::parts).anyMatch(c -> GOLD.equals(c.color())));
        assertTrue(help.stream().flatMap(MusicChatTest::parts).anyMatch(c -> AQUA.equals(c.color()) && c.hoverEvent() != null));
        assertTrue(clicks(help).contains(ClickEvent.suggestCommand("/minekumusic search ")));
        assertTrue(clicks(help).contains(ClickEvent.runCommand("/minekumusic queue")));
        assertTrue(clicks(help).stream().noneMatch(c -> command(c).contains("<") || command(c).contains("clear")));
        var admin = new MusicServer.Peer(player.id(), player.name(), true, true, true, true);
        assertTrue(clicks(MusicCommands.help(admin, MusicCommands.Mode.FOLIA, "clear")).contains(ClickEvent.suggestCommand("/minekumusic clear")));
        assertTrue(clicks(MusicCommands.help(MusicServer.console(), MusicCommands.Mode.FOLIA, "")).isEmpty());
    }
    @Test void searchButtonsPageThroughTenResultsAndUseStableSongIds() throws Exception {
        start(); List<Component> first = search("first query");
        assertEquals(10, clicks(first).stream().filter(c -> command(c).startsWith("/minekumusic request ")).count());
        assertNull(button(first, "上一页").clickEvent());
        assertTrue(clicks(first).contains(ClickEvent.runCommand("/minekumusic request 101")));
        List<Component> second = clickPage(first, "下一页");
        assertTrue(clicks(second).contains(ClickEvent.runCommand("/minekumusic request 111")));
        List<Component> third = clickPage(second, "下一页");
        assertEquals(3, clicks(third).stream().filter(c -> command(c).startsWith("/minekumusic request ")).count());
        assertNull(button(third, "下一页").clickEvent());
        List<Component> back = clickPage(third, "上一页");
        assertTrue(clicks(back).contains(ClickEvent.runCommand("/minekumusic request 111")));
        assertEquals(List.of(0, 1, 2, 1), searches.stream().map(SearchCall::page).toList());
        assertTrue(searches.stream().allMatch(s -> s.size == 10 && s.query.equals("first query")));
        assertTrue(messages.stream().allMatch(m -> m.player.equals(player.id())));
    }
    @Test void oldNavigationExpiresAfterAnotherSearchOrDisconnect() throws Exception {
        start(); List<Component> first = search("old query");
        List<Component> current = search("new query");
        String oldClick = command(button(first, "下一页").clickEvent());
        server.command(player, oldClick.substring("/minekumusic ".length()).split(" "));
        await(() -> notices.stream().anyMatch(s -> s.contains("已过期或已更新")));
        assertEquals(2, searches.size());
        server.disconnect(player.id());
        int before = notices.size();
        server.command(player, command(button(current, "下一页").clickEvent()).substring("/minekumusic ".length()).split(" "));
        await(() -> notices.size() > before);
        assertTrue(notices.getLast().contains("已过期或已更新"));
        assertEquals(2, searches.size());
    }
    @Test void failedPageCanBeRetriedAndEmptyResultsHaveNoRequestButtons() throws Exception {
        start(); List<Component> first = search("retry query");
        failNext.set(true); List<Component> error = clickPage(first, "下一页");
        assertTrue(plain(error).getFirst().contains("搜索失败")); assertEquals(RED, error.getFirst().color());
        List<Component> retried = clickPage(first, "下一页");
        assertTrue(clicks(retried).contains(ClickEvent.runCommand("/minekumusic request 111")));
        List<Component> empty = search("empty");
        assertTrue(plain(empty).stream().anyMatch(s -> s.contains("没有找到")));
        assertTrue(clicks(empty).stream().noneMatch(c -> c.action() == ClickEvent.Action.RUN_COMMAND));
    }
    @Test void readOnlyRecipientsGetInformationWithoutSongRequestActions() throws Exception {
        Track song = new Track(101, "Synthetic song", List.of("Fixture artist"), 180000, "");
        var readOnly = new MusicServer.Peer(player.id(), player.name(), true, false, true, false);
        List<Component> results = MusicChat.search(readOnly, "fixture", "fixture-id", 0, List.of(song), false);
        assertTrue(plain(results).stream().anyMatch(s -> s.contains("#101")));
        assertTrue(clicks(results).stream().noneMatch(c -> command(c).contains("request")));
        start();
        var denied = new MusicServer.Peer(player.id(), player.name(), false, false, false, false);
        server.command(denied, new String[]{"search", "fixture"});
        await(() -> !notices.isEmpty()); assertTrue(searches.isEmpty());
    }
}
