package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.protocol.MusicProtocol.Entry;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static me.sorihiko.dango.protocol.MusicProtocol.*;

public final class MusicServer implements AutoCloseable {
    public record Peer(UUID id, String name, boolean use, boolean request, boolean vote, boolean admin) {}
    public interface Endpoint {
        void send(UUID player, List<byte[]> frames);
        void tell(UUID player, String text);
        void votePrompt(UUID player, String text, String command);
        void log(String message, Throwable error);
    }
    private static final UUID CONSOLE = new UUID(0, 0);
    public static Peer console() { return new Peer(CONSOLE, "Console", true, true, true, true); }
    private final Endpoint endpoint;
    private final ServerConfig config;
    private final MusicProvider provider;
    private final Path stateFile;
    private final QueueEngine engine;
    private final String epoch = UUID.randomUUID().toString();
    private final ScheduledExecutorService owner = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Dango-Queue"));
    private final ExecutorService io = Executors.newFixedThreadPool(4, r -> new Thread(r, "Dango-Provider"));
    private final ExecutorService disk = Executors.newSingleThreadExecutor(r -> new Thread(r, "Dango-State"));
    private final Map<UUID, MusicProtocol.Assembler> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private MusicProvider.Audio audio;
    private String resolvingInstance = "";
    private boolean requesting;
    private long ticks, lastBroadcastRevision = -1;
    private static final class Session {
        final LinkedHashMap<String, JsonObject> results = new LinkedHashMap<>();
        final Set<String> pending = new HashSet<>();
        long window; int messages;
    }
    private record Request(Peer peer, String requestId, long songId, Session session) {}

    public MusicServer(Path directory, ServerConfig config, MusicProvider provider, Endpoint endpoint) throws Exception {
        this.endpoint = endpoint; this.config = config; this.provider = provider; stateFile = directory.resolve("queue.json");
        engine = new QueueEngine(System::nanoTime, config.playerLimit(), config.queueLimit(), config.cooldownSeconds());
        if (Files.exists(stateFile)) {
            try { engine.restore(JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject()); }
            catch (Exception e) { endpoint.log("Could not restore queue.json; starting an empty queue", e); }
        }
        owner.scheduleAtFixedRate(() -> guarded(() -> {
            if (engine.ended()) { engine.skip(); audio = null; }
            pump(); engine.pruneCooldowns();
            if (++ticks % config.syncSeconds() == 0 || lastBroadcastRevision != engine.revision()) broadcast();
            if (ticks % 5 == 0) persist();
        }), 0, 1, TimeUnit.SECONDS);
    }
    public void receive(Peer peer, byte[] bytes) {
        if (closed.get() || !peer.use()) return;
        try {
            JsonObject message = decoders.computeIfAbsent(peer.id(), id -> new MusicProtocol.Assembler()).accept(bytes);
            if (message != null) submit(() -> handle(peer, message));
        } catch (Exception e) { decoders.remove(peer.id()); endpoint.tell(peer.id(), "MineKUmusic: incompatible or incomplete message"); }
    }
    public void disconnect(UUID id) {
        decoders.remove(id);
        submit(() -> {
            sessions.remove(id);
            if (engine.unsubscribe(id.toString())) skipByVote();
            requests.removeIf(r -> r.peer.id().equals(id)); changed();
        });
    }
    private void handle(Peer peer, JsonObject message) {
        String type = string(message, "type", ""), rid = string(message, "requestId", "");
        JsonObject body = message.has("body") && message.get("body").isJsonObject() ? message.getAsJsonObject("body") : new JsonObject();
        if (rid.length() > 80) return;
        if (type.equals("hello")) {
            sessions.computeIfAbsent(peer.id(), id -> new Session());
            JsonObject welcome = new JsonObject();
            welcome.addProperty("version", VERSION); welcome.addProperty("serverId", config.serverId());
            welcome.addProperty("epoch", epoch);
            welcome.addProperty("channelName", config.channelName()); welcome.addProperty("playerId", peer.id().toString());
            welcome.addProperty("canRequest", peer.request()); welcome.addProperty("canVote", peer.vote());
            welcome.addProperty("admin", peer.admin());
            send(peer.id(), "welcome", rid, welcome); snapshot(peer.id(), rid, 0); return;
        }
        Session session = sessions.get(peer.id());
        if (session == null) return;
        long second = System.nanoTime() / 1_000_000_000L;
        if (session.window != second) { session.window = second; session.messages = 0; }
        if (++session.messages > 20) return;
        if (session.results.containsKey(rid)) { send(peer.id(), "result", rid, session.results.get(rid)); return; }
        if (session.pending.contains(rid)) return;
        try {
            switch (type) {
                case "subscribe" -> {
                    boolean wasJoined = engine.subscribed(peer.id().toString());
                    engine.subscribe(peer.id().toString()); result(peer, rid, true, "Joined server music"); changed(); sendAudio(peer.id());
                    if (!wasJoined && engine.votes() > 0) votePrompt(peer.id());
                }
                case "unsubscribe" -> {
                    if (engine.unsubscribe(peer.id().toString())) skipByVote();
                    result(peer, rid, true, "Left server music"); changed();
                }
                case "ping" -> snapshot(peer.id(), rid, number(body, "sentNanos", 0));
                case "queue" -> queuePage(peer.id(), rid, (int) number(body, "page", 0));
                case "search" -> search(peer, rid, string(body, "query", ""), (int) number(body, "page", 0));
                case "request" -> request(peer, rid, number(body, "songId", 0));
                case "withdraw" -> { engine.remove(peer.id().toString(), string(body, "entryId", ""), false); result(peer, rid, true, "Song withdrawn"); changed(); }
                case "vote" -> vote(peer, rid, string(body, "instance", ""));
                case "refreshAudio" -> refreshAudio(peer, rid, string(body, "instance", ""));
                default -> result(peer, rid, false, "Unknown music operation");
            }
        } catch (Exception e) { result(peer, rid, false, reason(e)); }
    }
    private void vote(Peer peer, String rid, String expectedInstance) {
        if (!peer.vote()) throw new QueueEngine.RuleException("You do not have permission to vote");
        if (!expectedInstance.isEmpty() && !engine.instance().equals(expectedInstance))
            throw new QueueEngine.RuleException("This skip vote has expired because the song changed");
        int previousVotes = engine.votes();
        boolean skip = engine.vote(peer.id().toString());
        if (engine.votes() == previousVotes) {
            result(peer, rid, true, "You have already voted for this song");
            return;
        }
        if (skip) skipByVote();
        else sessions.keySet().stream().filter(id -> engine.subscribed(id.toString())).forEach(this::votePrompt);
        result(peer, rid, true, "Vote recorded"); changed();
    }
    private void votePrompt(UUID id) {
        if (engine.current() == null) return;
        String text = "\u300a" + engine.current().track.name + "\u300b\u5207\u6b4c\u6295\u7968 "
                + engine.votes() + "/" + engine.votesRequired() + "\uff08" + engine.listeners()
                + " \u4eba\u6536\u542c\uff0c\u8fbe\u5230 15% \u5373\u5207\u6b4c\uff09";
        endpoint.votePrompt(id, text, "/dgm vote " + engine.instance());
    }
    private void tellListeners(String text) {
        sessions.keySet().stream().filter(id -> engine.subscribed(id.toString())).forEach(id -> endpoint.tell(id, text));
    }
    private void skipByVote() {
        if (engine.current() == null) return;
        tellListeners("\u5207\u6b4c\u6295\u7968\u5df2\u901a\u8fc7\uff08" + engine.votes() + "/" + engine.votesRequired()
                + "\uff0c\u6536\u542c\u4eba\u6570 " + engine.listeners() + "\uff0c\u9608\u503c 15%\uff09\uff0c\u5df2\u8df3\u8fc7\u300a"
                + engine.current().track.name + "\u300b");
        engine.skip(); audio = null;
    }
    private void request(Peer peer, String rid, long id) {
        if (!peer.request()) throw new QueueEngine.RuleException("You do not have permission to request songs");
        if (id <= 0) throw new QueueEngine.RuleException("Invalid song ID");
        engine.validateRequest(peer.id().toString(), id);
        if (requests.size() >= 100 || requests.stream().anyMatch(r -> r.peer.id().equals(peer.id())))
            throw new QueueEngine.RuleException("A song request is already being processed");
        Session session = sessions.get(peer.id());
        session.pending.add(rid); requests.add(new Request(peer, rid, id, session)); nextRequest();
    }
    private void nextRequest() {
        if (requesting || requests.isEmpty() || closed.get()) return;
        Request request = requests.remove(); requesting = true;
        CompletableFuture.supplyAsync(() -> resolve(request.songId), io).whenComplete((resolved, failure) -> submit(() -> {
            requesting = false;
            try {
                if (sessions.get(request.peer.id()) != request.session) return;
                if (failure != null) throw new IllegalStateException(reason(failure));
                engine.add(request.peer.id().toString(), request.peer.name(), resolved.track());
                result(request.peer, request.requestId, true, "Song added to the queue"); changed();
            } catch (Exception e) { result(request.peer, request.requestId, false, reason(e)); }
            finally { nextRequest(); }
        }));
    }
    private MusicProvider.Audio resolve(long id) {
        try { return provider.resolve(id); } catch (Exception e) { throw new CompletionException(e); }
    }
    private void search(Peer peer, String rid, String query, int page) {
        if (query.isBlank() || query.length() > 120 || page < 0 || page > 100) throw new IllegalArgumentException("Invalid search");
        Session session = sessions.get(peer.id());
        if (session.pending.size() >= 4) throw new IllegalArgumentException("Please wait for the current search");
        session.pending.add(rid);
        CompletableFuture.supplyAsync(() -> {
            try { return provider.search(query, page); } catch (Exception e) { throw new CompletionException(e); }
        }, io).whenComplete((found, failure) -> submit(() -> {
            if (sessions.get(peer.id()) != session) return;
            if (failure != null) { result(peer, rid, false, reason(failure)); return; }
            JsonObject body = new JsonObject(); body.addProperty("query", query); body.addProperty("page", page);
            body.addProperty("hasNext", found.hasNext()); body.add("tracks", GSON.toJsonTree(found.tracks()));
            send(peer.id(), "searchResults", rid, body); result(peer, rid, true, "");
        }));
    }
    private void pump() {
        Entry current = engine.selectNext();
        if (current == null) { audio = null; return; }
        if (engine.isReady() || engine.instance().equals(resolvingInstance)) return;
        String expected = engine.instance(); resolvingInstance = expected;
        CompletableFuture.supplyAsync(() -> resolve(current.track.id), io).whenComplete((resolved, failure) -> submit(() -> {
            if (!engine.instance().equals(expected)) return;
            resolvingInstance = "";
            if (failure != null) {
                sessions.keySet().forEach(id -> endpoint.tell(id, "MineKUmusic: " + current.track.name + ": " + reason(failure)));
                engine.skip(); audio = null;
            } else { audio = resolved; engine.ready(); sessions.keySet().forEach(this::sendAudio); }
            changed();
        }));
    }
    private void refreshAudio(Peer peer, String rid, String instance) {
        if (!engine.subscribed(peer.id().toString()) || !engine.instance().equals(instance) || engine.current() == null) return;
        Session session = sessions.get(peer.id());
        String retry = "audio:" + instance;
        if (session.results.containsKey(retry) || session.pending.contains(retry)) return;
        session.pending.add(retry);
        long id = engine.current().track.id;
        CompletableFuture.supplyAsync(() -> resolve(id), io).whenComplete((resolved, failure) -> submit(() -> {
            if (sessions.get(peer.id()) != session) return;
            session.pending.remove(retry); remember(session, retry, new JsonObject());
            if (!engine.instance().equals(instance) || !engine.subscribed(peer.id().toString())) return;
            if (failure != null) result(peer, rid, false, reason(failure));
            else sendAudio(peer.id(), resolved, true);
        }));
    }
    private void sendAudio(UUID id) { if (audio != null) sendAudio(id, audio, false); }
    private void sendAudio(UUID id, MusicProvider.Audio source, boolean refresh) {
        if (!engine.subscribed(id.toString()) || engine.current() == null) return;
        JsonObject body = engine.state();
        body.addProperty("url", source.url()); body.addProperty("lrc", source.lrc()); body.addProperty("yrc", source.yrc());
        body.addProperty("refresh", refresh); send(id, "audio", "", body);
    }
    private void snapshot(UUID id, String rid, long sentNanos) {
        JsonObject body = engine.state(); body.addProperty("serverId", config.serverId());
        body.addProperty("subscribed", engine.subscribed(id.toString())); body.addProperty("sentNanos", sentNanos);
        send(id, "snapshot", rid, body);
    }
    private void queuePage(UUID id, String rid, int requestedPage) {
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (engine.queue().size() - 1) / PAGE_SIZE)));
        int from = page * PAGE_SIZE, to = Math.min(from + PAGE_SIZE, engine.queue().size());
        JsonObject body = new JsonObject(); body.addProperty("page", page); body.addProperty("total", engine.queue().size());
        body.addProperty("revision", engine.revision()); body.addProperty("hasNext", to < engine.queue().size());
        body.add("entries", GSON.toJsonTree(engine.queue().subList(from, to))); send(id, "queuePage", rid, body);
    }
    private void broadcast() { for (UUID id : new ArrayList<>(sessions.keySet())) snapshot(id, "", 0); lastBroadcastRevision = engine.revision(); }
    private void changed() { pump(); broadcast(); persist(); }
    private void result(Peer peer, String rid, boolean ok, String text) {
        Session session = sessions.get(peer.id());
        JsonObject body = new JsonObject(); body.addProperty("ok", ok); body.addProperty("message", text);
        if (session != null) { session.pending.remove(rid); remember(session, rid, body); send(peer.id(), "result", rid, body); }
        if (rid.startsWith("cmd:") || session == null) if (!text.isEmpty()) endpoint.tell(peer.id(), text);
    }
    private void remember(Session session, String rid, JsonObject result) {
        session.results.put(rid, result);
        while (session.results.size() > 128) session.results.remove(session.results.keySet().iterator().next());
    }
    private void send(UUID id, String type, String rid, JsonObject body) {
        body.addProperty("serverId", config.serverId());
        if (!closed.get()) endpoint.send(id, MusicProtocol.frames(MusicProtocol.message(type, rid, body)));
    }
    public void command(Peer peer, String[] args) {
        submit(() -> {
            String cmd = args.length == 0 ? "queue" : args[0].toLowerCase(Locale.ROOT);
            String rid = "cmd:" + UUID.randomUUID();
            try {
                if (!peer.use()) throw new IllegalArgumentException("You do not have permission to use server music");
                switch (cmd) {
                    case "queue" -> {
                        endpoint.tell(peer.id(), engine.current() == null ? "Nothing is playing" : "Now: " + engine.current().track.name);
                        int page = args.length > 1 ? Math.max(0, Integer.parseInt(args[1]) - 1) : 0;
                        engine.queue().stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).forEach(e -> endpoint.tell(peer.id(), e.entryId + " | " + e.track.name + " | " + e.requesterName));
                    }
                    case "request" -> { if (args.length < 2) throw new IllegalArgumentException("/dgm request <songId>"); request(peer, rid, Long.parseLong(args[1])); }
                    case "withdraw" -> { if (args.length < 2) throw new IllegalArgumentException("/dgm withdraw <entryId>"); engine.remove(peer.id().toString(), args[1], false); changed(); result(peer, rid, true, "Song withdrawn"); }
                    case "vote" -> vote(peer, rid, args.length > 1 ? args[1] : "");
                    case "search" -> {
                        if (args.length < 2) throw new IllegalArgumentException("/dgm search <keywords>");
                        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                        if (query.length() > 120) throw new IllegalArgumentException("Search is too long");
                        CompletableFuture.supplyAsync(() -> { try { return provider.search(query, 0); } catch (Exception e) { throw new CompletionException(e); } }, io)
                                .whenComplete((found, error) -> submit(() -> {
                                    if (error != null) endpoint.tell(peer.id(), reason(error));
                                    else found.tracks().forEach(t -> endpoint.tell(peer.id(), t.id + " | " + t.name + " | " + String.join(" / ", t.artists)));
                                }));
                    }
                    case "skip", "pause", "resume", "clear", "remove" -> {
                        if (!peer.admin()) throw new IllegalArgumentException("Administrator permission required");
                        switch (cmd) {
                            case "skip" -> {
                                if (engine.current() != null) tellListeners("\u7ba1\u7406\u5458\u5df2\u8df3\u8fc7\u300a" + engine.current().track.name + "\u300b");
                                engine.skip(); audio = null;
                            }
                            case "pause" -> engine.pause(true);
                            case "resume" -> engine.pause(false);
                            case "clear" -> { engine.clear(); audio = null; }
                            case "remove" -> { if (args.length < 2) throw new IllegalArgumentException("/dgm remove <entryId>"); engine.remove(peer.id().toString(), args[1], true); }
                        }
                        changed(); endpoint.tell(peer.id(), "Music channel updated");
                    }
                    default -> endpoint.tell(peer.id(), "/dgm queue [page] | search <keywords> | request <songId> | withdraw <entryId> | vote | skip | pause | resume | clear | remove <entryId>");
                }
            } catch (Exception e) { result(peer, rid, false, reason(e)); }
        });
    }
    private void persist() {
        String data = GSON.toJson(engine.save());
        if (!disk.isShutdown()) disk.execute(() -> {
            try {
                Path temp = stateFile.resolveSibling("queue.json.tmp"); Files.writeString(temp, data);
                try { Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING); }
            } catch (Exception e) { endpoint.log("Could not save the music queue", e); }
        });
    }
    private void submit(Runnable task) { if (!closed.get()) try { owner.execute(() -> guarded(task)); } catch (RejectedExecutionException ignored) {} }
    private void guarded(Runnable task) { if (!closed.get()) try { task.run(); } catch (Exception e) { endpoint.log("Music operation failed", e); } }
    private static String reason(Throwable e) {
        while (e.getCause() != null && (e instanceof CompletionException || e instanceof ExecutionException)) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        owner.execute(() -> {
            persist(); sessions.clear(); requests.clear();
            disk.execute(() -> termination.complete(null)); disk.shutdown();
        });
        owner.shutdown(); provider.close(); io.shutdownNow(); decoders.clear();
    }
    public CompletionStage<Void> termination() { return termination; }
}
