package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.protocol.MusicProtocol.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class MusicServerTest {
    @TempDir Path directory;
    private final List<MusicServer> instances = new ArrayList<>();
    private MusicServer track(MusicServer server) { instances.add(server); return server; }
    @AfterEach void awaitShutdown() throws Exception {
        for (MusicServer server : instances) { server.close(); server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS); }
    }
    private static final class Wire implements MusicServer.Endpoint {
        record Chat(UUID player, String text) {}
        record Prompt(UUID player, String text, String command) {}
        final Map<UUID, List<JsonObject>> messages = new ConcurrentHashMap<>();
        final Map<UUID, MusicProtocol.Assembler> assemblers = new ConcurrentHashMap<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<Chat> chat = new CopyOnWriteArrayList<>();
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        @Override public void send(UUID id, List<byte[]> frames) {
            try {
                for (byte[] frame : frames) {
                    JsonObject message = assemblers.computeIfAbsent(id, key -> new MusicProtocol.Assembler()).accept(frame);
                    if (message != null) messages.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()).add(message);
                }
            } catch (Exception e) { errors.add(e); }
        }
        @Override public void tell(UUID id, String text) { chat.add(new Chat(id, text)); }
        @Override public void votePrompt(UUID id, String text, String command) { prompts.add(new Prompt(id, text, command)); }
        @Override public void log(String text, Throwable error) { errors.add(error); }
        List<JsonObject> of(UUID id, String type) { return messages.getOrDefault(id, List.of()).stream().filter(m -> m.get("type").getAsString().equals(type)).toList(); }
        JsonObject last(UUID id, String type) { return of(id, type).getLast().getAsJsonObject("body"); }
    }
    private MusicServer.Peer peer(String name) { return new MusicServer.Peer(UUID.randomUUID(), name, true, true, true, false); }
    private MusicProvider provider() {
        return new MusicProvider() {
            @Override public Search search(String query, int page, int pageSize) { return new Search(List.of(track(100 + page)), page == 0); }
            @Override public Audio resolve(long id) { return new Audio(track(id), "https://example.invalid/synthetic-test.mp3", "[00:01.00]Synthetic test lyrics", ""); }
            private Track track(long id) { return new Track(id, "Synthetic test song", List.of("Fixture artist"), 180000, ""); }
        };
    }
    private MusicServer server(Wire wire) throws Exception { return track(new MusicServer(directory, new ServerConfig("test-network", "Fixture channel", 3, 100, 0, 1), provider(), wire)); }
    private void send(MusicServer server, MusicServer.Peer peer, String type, String rid, JsonObject body) {
        for (byte[] bytes : MusicProtocol.frames(MusicProtocol.message(type, rid, body))) server.receive(peer, bytes);
    }
    private void send(MusicServer server, MusicServer.Peer peer, String type) { send(server, peer, type, UUID.randomUUID().toString(), new JsonObject()); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Expected music event was not received");
    }
    @Test void rootHelpAndInvalidArgumentsProduceUsefulChatResponses() throws Exception {
        Wire wire = new Wire(); var a = peer("Synthetic command user");
        try (MusicServer server = server(wire)) {
            server.command(a, new String[0]);
            await(() -> wire.chat.stream().anyMatch(c -> c.text.contains("指令帮助")));
            server.command(a, new String[]{"queue", "not-a-page"});
            await(() -> wire.chat.stream().anyMatch(c -> c.text.contains("参数必须是有效的数字")));
            server.command(a, new String[]{"help", "request"});
            await(() -> wire.chat.stream().anyMatch(c -> c.text.contains("确认点歌费用")));
            assertTrue(wire.errors.isEmpty(), wire.errors.toString());
        }
    }
    @Test void reloadPreservesPlaybackListenersAndQueuedEntriesWhenLimitsShrink() throws Exception {
        Wire wire = new Wire(); var a = peer("Synthetic reload user");
        try (MusicServer server = server(wire)) {
            send(server, a, "hello"); send(server, a, "subscribe");
            for (int i = 0; i < 4; i++) {
                String rid = "reload-song-" + i;
                JsonObject body = new JsonObject(); body.addProperty("songId", 101 + i);
                send(server, a, "request", rid, body);
                await(() -> wire.of(a.id(), "result").stream().anyMatch(m -> m.get("requestId").getAsString().equals(rid)));
            }
            await(() -> !wire.of(a.id(), "audio").isEmpty());
            server.command(MusicServer.console(), new String[]{"pause"});
            await(() -> wire.last(a.id(), "snapshot").get("paused").getAsBoolean());
            JsonObject before = wire.last(a.id(), "snapshot");
            List<String> entries = server.suggest(a, new String[]{"withdraw", ""}, MusicCommands.Mode.FOLIA);
            assertEquals(3, entries.size());
            Files.writeString(directory.resolve("config.properties"), "server-id=test-network\nchannel-name=合成重载频道\nplayer-queue-limit=1\nqueue-limit=1\nrequest-cooldown-seconds=0\nsync-seconds=3\n");
            server.command(MusicServer.console(), new String[]{"reload"});
            await(() -> wire.chat.stream().anyMatch(c -> c.text.startsWith("配置重载成功")));
            JsonObject after = wire.last(a.id(), "snapshot");
            assertEquals(before.get("instance"), after.get("instance"));
            assertEquals(before.get("positionMs"), after.get("positionMs"));
            assertTrue(after.get("paused").getAsBoolean()); assertEquals(1, after.get("listeners").getAsInt());
            assertEquals(3, after.get("queueSize").getAsInt());
            assertEquals(entries, server.suggest(a, new String[]{"withdraw", ""}, MusicCommands.Mode.FOLIA));
            assertEquals("合成重载频道", wire.last(a.id(), "welcome").get("channelName").getAsString());
            server.close(); server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            QueueEngine restored = new QueueEngine(System::nanoTime, 1, 1, 0);
            restored.restore(JsonParser.parseString(Files.readString(directory.resolve("queue.json"))).getAsJsonObject());
            assertEquals(3, restored.queue().size());
            assertTrue(wire.errors.isEmpty(), wire.errors.toString());
        }
    }
    @Test void invalidReloadKeepsTheRunningConfigurationAndCorrectedReloadSucceeds() throws Exception {
        Wire wire = new Wire(); var a = peer("Synthetic config user");
        try (MusicServer server = server(wire)) {
            send(server, a, "hello"); await(() -> !wire.of(a.id(), "welcome").isEmpty());
            Files.writeString(directory.resolve("config.properties"), "server-id=test-network\nchannel-name=Invalid update\nqueue-limit=0\n");
            server.command(MusicServer.console(), new String[]{"reload"});
            await(() -> wire.chat.stream().anyMatch(c -> c.text.startsWith("配置重载失败")));
            send(server, a, "hello", "after-invalid-reload", new JsonObject());
            await(() -> wire.of(a.id(), "welcome").stream().anyMatch(m -> m.get("requestId").getAsString().equals("after-invalid-reload")));
            assertEquals("Fixture channel", wire.last(a.id(), "welcome").get("channelName").getAsString());
            Files.writeString(directory.resolve("config.properties"), "server-id=test-network\nchannel-name=Corrected update\nqueue-limit=10\n");
            server.command(MusicServer.console(), new String[]{"reload"});
            await(() -> wire.chat.stream().anyMatch(c -> c.text.startsWith("配置重载成功")));
            assertEquals("Corrected update", wire.last(a.id(), "welcome").get("channelName").getAsString());
            assertTrue(wire.errors.isEmpty(), wire.errors.toString());
        }
    }
    @Test void optInSharedPlaybackRehandshakeAndVoteWorkEndToEnd() throws Exception {
        Wire wire = new Wire(); var a = peer("A"); var b = peer("B");
        try (MusicServer server = server(wire)) {
            send(server, a, "hello"); send(server, b, "hello"); send(server, a, "subscribe");
            JsonObject request = new JsonObject(); request.addProperty("songId", 101);
            send(server, a, "request", "same-request", request);
            await(() -> !wire.of(a.id(), "audio").isEmpty());
            assertTrue(wire.of(b.id(), "audio").isEmpty());
            String instance = wire.last(a.id(), "audio").get("instance").getAsString();
            send(server, b, "subscribe"); await(() -> !wire.of(b.id(), "audio").isEmpty());
            assertEquals(instance, wire.last(b.id(), "audio").get("instance").getAsString());
            send(server, b, "hello"); send(server, b, "subscribe");
            send(server, a, "request", "same-request", request);
            send(server, a, "queue"); await(() -> !wire.of(a.id(), "queuePage").isEmpty());
            assertEquals(0, wire.last(a.id(), "queuePage").get("total").getAsInt());
            assertEquals(2, wire.last(b.id(), "snapshot").get("listeners").getAsInt());
            assertEquals(1, wire.last(b.id(), "snapshot").get("votesRequired").getAsInt());
            send(server, a, "vote");
            await(() -> wire.last(a.id(), "snapshot").get("current").isJsonNull());
            assertEquals(2, wire.chat.stream().filter(c -> c.text().contains("\u5207\u6b4c\u6295\u7968\u5df2\u901a\u8fc7")).count());
            assertTrue(wire.errors.isEmpty());
        }
    }
    @Test void chatVotesReachOnlySubscribersAndOldPromptsCannotSkipTheNextSong() throws Exception {
        Wire wire = new Wire();
        var listeners = new ArrayList<MusicServer.Peer>();
        for (int i = 0; i < 7; i++) listeners.add(peer("Fixture listener " + i));
        var observer = peer("Not subscribed"); var a = listeners.getFirst(); var b = listeners.get(1);
        try (MusicServer server = server(wire)) {
            send(server, observer, "hello");
            for (var listener : listeners) { send(server, listener, "hello"); send(server, listener, "subscribe"); }
            JsonObject request = new JsonObject(); request.addProperty("songId", 101);
            send(server, a, "request", "first", request);
            await(() -> !wire.of(a.id(), "audio").isEmpty());
            String instance = wire.last(a.id(), "audio").get("instance").getAsString();
            request = new JsonObject(); request.addProperty("songId", 102);
            send(server, a, "request", "second", request);
            await(() -> wire.of(a.id(), "result").stream().anyMatch(m -> m.get("requestId").getAsString().equals("second")));
            JsonObject vote = new JsonObject(); vote.addProperty("instance", instance);
            send(server, a, "vote", "first-vote", vote);
            await(() -> wire.prompts.size() == 7);
            assertTrue(wire.prompts.stream().noneMatch(p -> p.player().equals(observer.id())));
            assertTrue(wire.prompts.stream().allMatch(p -> p.command().equals("/minekumusic vote " + instance) && p.text().contains("1/2")));
            String[] click = wire.prompts.getFirst().command().substring("/minekumusic ".length()).split(" ");
            server.command(a, click);
            await(() -> wire.chat.stream().anyMatch(c -> c.player().equals(a.id()) && c.text().equals("You have already voted for this song")));
            assertEquals(7, wire.prompts.size());
            assertEquals(1, wire.last(a.id(), "snapshot").get("votes").getAsInt());
            server.command(b, click);
            await(() -> wire.of(a.id(), "audio").size() == 2);
            String next = wire.last(a.id(), "audio").get("instance").getAsString();
            assertNotEquals(instance, next);
            assertEquals(102, wire.last(a.id(), "audio").getAsJsonObject("current").getAsJsonObject("track").get("id").getAsLong());
            assertEquals(7, wire.chat.stream().filter(c -> c.text().contains("\u5207\u6b4c\u6295\u7968\u5df2\u901a\u8fc7")).count());
            assertTrue(wire.chat.stream().noneMatch(c -> c.player().equals(observer.id())));
            server.command(listeners.get(2), click);
            await(() -> wire.chat.stream().anyMatch(c -> c.text().equals("This skip vote has expired because the song changed")));
            send(server, a, "vote", "old-ui-vote", vote);
            await(() -> wire.of(a.id(), "result").stream().anyMatch(m -> m.get("requestId").getAsString().equals("old-ui-vote")));
            assertFalse(wire.last(a.id(), "result").get("ok").getAsBoolean());
            send(server, a, "ping");
            await(() -> wire.last(a.id(), "snapshot").get("instance").getAsString().equals(next));
            assertEquals(0, wire.last(a.id(), "snapshot").get("votes").getAsInt());
            assertEquals(7, wire.prompts.size()); assertTrue(wire.errors.isEmpty(), wire.errors.toString());
        }
    }
    @Test void unsubscribeCanPassVoteAndAnnouncesOnlyToRemainingListeners() throws Exception { leavingCanPassVote(false); }
    @Test void disconnectCanPassVoteAndAnnouncesOnlyToRemainingListeners() throws Exception { leavingCanPassVote(true); }
    private void leavingCanPassVote(boolean disconnect) throws Exception {
        Wire wire = new Wire(); var listeners = new ArrayList<MusicServer.Peer>();
        for (int i = 0; i < 7; i++) listeners.add(peer("Fixture listener " + i));
        var a = listeners.getFirst(); var leaving = listeners.getLast();
        try (MusicServer server = server(wire)) {
            for (var listener : listeners) { send(server, listener, "hello"); send(server, listener, "subscribe"); }
            JsonObject request = new JsonObject(); request.addProperty("songId", 101);
            send(server, a, "request", "request", request); await(() -> !wire.of(a.id(), "audio").isEmpty());
            send(server, a, "vote"); await(() -> wire.last(a.id(), "snapshot").get("votes").getAsInt() == 1);
            if (disconnect) server.disconnect(leaving.id()); else send(server, leaving, "unsubscribe");
            await(() -> wire.last(a.id(), "snapshot").get("current").isJsonNull());
            var announcements = wire.chat.stream().filter(c -> c.text().contains("\u5207\u6b4c\u6295\u7968\u5df2\u901a\u8fc7")).toList();
            assertEquals(6, announcements.size());
            assertTrue(announcements.stream().noneMatch(c -> c.player().equals(leaving.id())));
            assertEquals(6, wire.last(a.id(), "snapshot").get("listeners").getAsInt());
            assertTrue(wire.errors.isEmpty(), wire.errors.toString());
        }
    }
    @Test void searchIsPagedAndShutdownPersistsCurrentSong() throws Exception {
        Wire wire = new Wire(); var a = peer("A");
        MusicServer initial = server(wire);
        try (MusicServer server = initial) {
            send(server, a, "hello"); send(server, a, "subscribe");
            JsonObject search = new JsonObject(); search.addProperty("query", "fixture"); search.addProperty("page", 1);
            send(server, a, "search", "search-page", search);
            await(() -> !wire.of(a.id(), "searchResults").isEmpty());
            assertEquals(1, wire.last(a.id(), "searchResults").get("page").getAsInt());
            JsonObject request = new JsonObject(); request.addProperty("songId", 101);
            send(server, a, "request", "request", request); await(() -> !wire.of(a.id(), "audio").isEmpty());
        }
        initial.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        await(() -> {
            try { return Files.exists(directory.resolve("queue.json")) && !JsonParser.parseString(Files.readString(directory.resolve("queue.json"))).getAsJsonObject().get("current").isJsonNull(); }
            catch (Exception e) { return false; }
        });
        Wire restarted = new Wire();
        MusicServer second = server(restarted);
        try (MusicServer server = second) {
            send(server, a, "hello"); send(server, a, "subscribe");
            await(() -> !restarted.of(a.id(), "audio").isEmpty());
            assertEquals(101, restarted.last(a.id(), "audio").getAsJsonObject("current").getAsJsonObject("track").get("id").getAsLong());
        }
        second.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(wire.errors.isEmpty(), wire.errors.toString()); assertTrue(restarted.errors.isEmpty(), restarted.errors.toString());
    }
    @Test void resolvingRequestFromDisconnectedSessionCannotEnterNewSession() throws Exception {
        Wire wire = new Wire(); var a = peer("A");
        CountDownLatch resolving = new CountDownLatch(1), release = new CountDownLatch(1);
        MusicProvider delayed = new MusicProvider() {
            @Override public Search search(String query, int page, int pageSize) { return new Search(List.of(), false); }
            @Override public Audio resolve(long id) throws Exception {
                if (id == 101) { resolving.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
                return new Audio(new Track(id, "Synthetic delayed song", List.of("Fixture"), 180000, ""),
                        "https://example.invalid/synthetic-test.mp3", "", "");
            }
        };
        try (MusicServer server = track(new MusicServer(directory, new ServerConfig("test", "Test", 3, 100, 0, 1), delayed, wire))) {
            send(server, a, "hello"); send(server, a, "subscribe");
            JsonObject old = new JsonObject(); old.addProperty("songId", 101);
            send(server, a, "request", "old-session-request", old);
            assertTrue(resolving.await(5, TimeUnit.SECONDS));
            server.disconnect(a.id()); send(server, a, "hello"); send(server, a, "subscribe");
            JsonObject fresh = new JsonObject(); fresh.addProperty("songId", 102);
            send(server, a, "request", "new-session-request", fresh); release.countDown();
            await(() -> !wire.of(a.id(), "audio").isEmpty());
            assertEquals(102, wire.last(a.id(), "audio").getAsJsonObject("current").getAsJsonObject("track").get("id").getAsLong());
            assertTrue(wire.of(a.id(), "result").stream().noneMatch(m -> m.get("requestId").getAsString().equals("old-session-request")));
            assertTrue(wire.errors.isEmpty(), wire.errors.toString());
        } finally { release.countDown(); }
    }
}
