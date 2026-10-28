package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.protocol.MusicProtocol.Entry;
import net.kyori.adventure.text.Component;
import java.math.BigDecimal;
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
        default void notice(UUID player, Component content) { tell(player, MusicChat.plain(content)); }
        default void chat(UUID player, List<Component> lines) { lines.forEach(line -> tell(player, MusicChat.plain(line))); }
        void announce(Component content);
        void votePrompt(UUID player, String text, String command);
        default void votePrompt(UUID player, Component text, String command) { votePrompt(player, MusicChat.plain(text), command); }
        void log(String message, Throwable error);
    }
    private static final UUID CONSOLE = new UUID(0, 0);
    public static Peer console() { return new Peer(CONSOLE, "Console", true, true, true, true); }
    private final Endpoint endpoint;
    private ServerConfig config;
    private volatile MusicMessages messages;
    public MusicMessages messages() { return messages; }
    private final MusicProvider provider;
    private final EconomyService economy;
    private final Path stateFile;
    private final QueueEngine engine;
    private final String epoch = UUID.randomUUID().toString();
    private final ScheduledExecutorService owner = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Dango-Queue"));
    private final ExecutorService io = Executors.newFixedThreadPool(4, r -> new Thread(r, "Dango-Provider"));
    private final ExecutorService disk = Executors.newSingleThreadExecutor(r -> new Thread(r, "Dango-State"));
    private final Map<UUID, MusicProtocol.Assembler> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    // Owned exclusively by the queue executor; one search per sender, expired or removed on disconnect.
    private final Map<UUID, ChatSearch> chatSearches = new HashMap<>();
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private MusicProvider.Audio audio;
    private String resolvingInstance = "";
    private boolean requesting;
    private boolean paying;
    private Purchase purchase;
    private long paymentRetryAt;
    private String paymentNotice = "";
    private long ticks, lastBroadcastRevision = -1;
    private boolean reloading;
    private volatile MusicCommands.Snapshot commandState = MusicCommands.Snapshot.EMPTY;
    private static final class Session {
        final LinkedHashMap<String, JsonObject> results = new LinkedHashMap<>();
        final Set<String> pending = new HashSet<>();
        long window; int messages;
        Quote quote;
        boolean chatJoin;
        Peer peer;
        Session(Peer peer) { this.peer = peer; }
    }
    private record Quote(String id, long songId, BigDecimal amount, String currency, long expires) {}
    private record Request(Peer peer, String requestId, long songId, Session session, Quote quote) {}
    private record Purchase(EconomyService.Payment payment, MusicProtocol.Track track, String requestId, String currency) {}
    private static final class ChatSearch {
        final String id = UUID.randomUUID().toString();
        final String query;
        int page;
        boolean hasNext, loading;
        long expires;
        ChatSearch(String query) { this.query = query; }
    }

    public MusicServer(Path directory, ServerConfig config, MusicProvider provider, Endpoint endpoint) throws Exception {
        this(directory, config, provider, endpoint, EconomyService.UNAVAILABLE);
    }
    public MusicServer(Path directory, ServerConfig config, MusicProvider provider, Endpoint endpoint, EconomyService economy) throws Exception {
        this(directory, config, provider, endpoint, economy, MusicMessages.load(directory));
    }
    public MusicServer(Path directory, ServerConfig config, MusicProvider provider, Endpoint endpoint, EconomyService economy, MusicMessages messages) throws Exception {
        this.messages = messages;
        this.endpoint = endpoint; this.config = config; this.provider = provider; this.economy = economy; stateFile = directory.resolve("queue.json");
        engine = new QueueEngine(System::nanoTime, config.playerLimit(), config.queueLimit(), config.cooldownSeconds());
        if (Files.exists(stateFile)) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
                engine.restore(saved);
                if (saved.has("pendingPurchase") && !saved.get("pendingPurchase").isJsonNull())
                    purchase = GSON.fromJson(saved.get("pendingPurchase"), Purchase.class);
            }
            catch (Exception e) { endpoint.log("Could not restore queue.json; starting an empty queue", e); }
        }
        owner.scheduleAtFixedRate(() -> guarded(() -> {
            if (engine.ended()) { engine.skip(); audio = null; }
            pump(); engine.pruneCooldowns();
            chatSearches.values().removeIf(search -> System.nanoTime() >= search.expires);
            if (purchase != null && !paying && System.nanoTime() >= paymentRetryAt) pay();
            if (++ticks % this.config.syncSeconds() == 0 || lastBroadcastRevision != engine.revision()) broadcast();
            if (ticks % 5 == 0) persist();
        }), 0, 1, TimeUnit.SECONDS);
    }
    public void receive(Peer peer, byte[] bytes) {
        if (closed.get() || !peer.use()) return;
        try {
            JsonObject message = decoders.computeIfAbsent(peer.id(), id -> new MusicProtocol.Assembler()).accept(bytes);
            if (message != null) submit(() -> handle(peer, message));
        } catch (Exception e) { decoders.remove(peer.id()); tell(peer.id(), "protocol.invalid"); }
    }
    public void disconnect(UUID id) {
        decoders.remove(id);
        submit(() -> {
            sessions.remove(id); chatSearches.remove(id);
            if (engine.unsubscribe(id.toString())) skipByVote();
            requests.removeIf(r -> r.peer.id().equals(id)); changed();
        });
    }
    private void handle(Peer peer, JsonObject message) {
        String type = string(message, "type", ""), rid = string(message, "requestId", "");
        JsonObject body = message.has("body") && message.get("body").isJsonObject() ? message.getAsJsonObject("body") : new JsonObject();
        if (rid.length() > 80) return;
        if (type.equals("hello")) {
            Session session = sessions.computeIfAbsent(peer.id(), id -> new Session(peer));
            session.peer = peer;
            session.chatJoin = bool(body, "chatJoin", false);
            welcome(peer, rid); snapshot(peer.id(), rid, 0); return;
        }
        Session session = sessions.get(peer.id());
        if (session == null) return;
        session.peer = peer;
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
                    session.quote = null;
                    if (engine.unsubscribe(peer.id().toString())) skipByVote();
                    result(peer, rid, true, "Left server music"); changed();
                }
                case "ping" -> snapshot(peer.id(), rid, number(body, "sentNanos", 0));
                case "queue" -> queuePage(peer.id(), rid, (int) number(body, "page", 0));
                case "search" -> search(peer, rid, string(body, "query", ""), (int) number(body, "page", 0));
                case "request" -> request(peer, rid, number(body, "songId", 0));
                case "quoteRequest" -> quote(peer, rid, number(body, "songId", 0));
                case "confirmRequest" -> confirmRequest(peer, rid, string(body, "quoteId", ""));
                case "cancelRequest" -> {
                    if (session.quote != null && session.quote.id.equals(string(body, "quoteId", ""))) session.quote = null;
                    result(peer, rid, true, "Song request cancelled");
                }
                case "withdraw" -> { engine.remove(peer.id().toString(), string(body, "entryId", ""), false); result(peer, rid, true, "Song withdrawn"); changed(); }
                case "vote" -> vote(peer, rid, string(body, "instance", ""));
                case "refreshAudio" -> refreshAudio(peer, rid, string(body, "instance", ""));
                default -> result(peer, rid, false, "Unknown music operation");
            }
        } catch (Exception e) { result(peer, rid, false, reason(e)); }
    }
    private void welcome(Peer peer, String rid) {
        JsonObject welcome = new JsonObject();
        welcome.addProperty("version", VERSION); welcome.addProperty("serverId", config.serverId());
        welcome.addProperty("epoch", epoch);
        welcome.addProperty("channelName", config.channelName()); welcome.addProperty("playerId", peer.id().toString());
        welcome.addProperty("canRequest", peer.request()); welcome.addProperty("canVote", peer.vote()); welcome.addProperty("admin", peer.admin());
        welcome.addProperty("requestConfirmation", true);
        welcome.addProperty("requestPrice", config.requestPrice().toPlainString()); welcome.addProperty("currencyName", config.currencyName());
        send(peer.id(), "welcome", rid, welcome);
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
        endpoint.votePrompt(id, messages.render("vote.prompt", "song", engine.current().track.name,
                "votes", engine.votes(), "required", engine.votesRequired(), "listeners", engine.listeners()), "/minekumusic vote " + engine.instance());
    }
    private void tell(UUID id, String key, Object... args) { endpoint.notice(id, messages.render(key, args)); }
    private void announceRequest(Entry entry) {
        try { endpoint.announce(MusicChat.requestAnnouncement(messages, config.channelName(), entry.requesterName, entry.track)); }
        catch (Exception e) { endpoint.log("Could not announce the song request", e); }
    }
    private void joinFromChat(Peer peer) {
        Session session = sessions.get(peer.id());
        if (session == null) { tell(peer.id(), "join.client-missing"); return; }
        if (engine.subscribed(peer.id().toString())) { tell(peer.id(), "join.already"); return; }
        if (!session.chatJoin) { tell(peer.id(), "join.client-update"); return; }
        JsonObject body = new JsonObject(); body.addProperty("epoch", epoch);
        send(peer.id(), "joinChannel", "", body);
        tell(peer.id(), "join.connecting", "channel", config.channelName());
    }
    private void tellListeners(String key, Object... args) {
        Component content = messages.render(key, args);
        sessions.keySet().stream().filter(id -> engine.subscribed(id.toString())).forEach(id -> endpoint.notice(id, content));
    }
    private void skipByVote() {
        if (engine.current() == null) return;
        tellListeners("vote.passed", "votes", engine.votes(), "required", engine.votesRequired(),
                "listeners", engine.listeners(), "song", engine.current().track.name);
        engine.skip(); audio = null;
    }
    private void request(Peer peer, String rid, long id) {
        if (config.requestPrice().signum() > 0) throw new QueueEngine.RuleException("Update MineKU Music and confirm the request price before requesting songs");
        enqueue(peer, rid, id, new Quote("", id, BigDecimal.ZERO, config.currencyName(), 0));
    }
    private void validateRequest(Peer peer, long id) {
        if (!peer.request()) throw new QueueEngine.RuleException("You do not have permission to request songs");
        if (id <= 0) throw new QueueEngine.RuleException("Invalid song ID");
        engine.validateRequest(peer.id().toString(), id);
        if (purchase != null) throw new QueueEngine.RuleException("A payment is still being checked; please wait");
        if (requests.size() >= 100 || requests.stream().anyMatch(r -> r.peer.id().equals(peer.id())))
            throw new QueueEngine.RuleException("A song request is already being processed");
        if (!sessions.containsKey(peer.id())) throw new QueueEngine.RuleException("Join the channel using MineKU Music before requesting songs");
    }
    private void quote(Peer peer, String rid, long id) {
        validateRequest(peer, id);
        Quote quote = new Quote(UUID.randomUUID().toString(), id, config.requestPrice(), config.currencyName(), System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
        sessions.get(peer.id()).quote = quote;
        JsonObject body = new JsonObject(); body.addProperty("quoteId", quote.id); body.addProperty("songId", id);
        body.addProperty("amount", quote.amount.toPlainString()); body.addProperty("currencyName", quote.currency); body.addProperty("expiresInMs", 60000);
        send(peer.id(), "requestQuote", rid, body);
        result(peer, rid, true, "Confirm the song request in the music player");
    }
    private void confirmRequest(Peer peer, String rid, String quoteId) {
        Session session = sessions.get(peer.id());
        Quote quote = session == null ? null : session.quote;
        if (quote == null || !quote.id.equals(quoteId) || System.nanoTime() >= quote.expires)
            throw new QueueEngine.RuleException("The song request confirmation expired; please select the song again");
        session.quote = null;
        enqueue(peer, rid, quote.songId, quote);
    }
    private void enqueue(Peer peer, String rid, long id, Quote quote) {
        validateRequest(peer, id);
        if (quote.songId != id || quote.amount.compareTo(config.requestPrice()) != 0)
            throw new QueueEngine.RuleException("The song request confirmation expired; please select the song again");
        Session session = sessions.get(peer.id());
        session.pending.add(rid); requests.add(new Request(peer, rid, id, session, quote)); nextRequest();
    }
    private void nextRequest() {
        if (requesting || purchase != null || requests.isEmpty() || closed.get()) return;
        Request request = requests.remove(); requesting = true;
        CompletableFuture.supplyAsync(() -> resolve(request.songId), io).whenComplete((resolved, failure) -> submit(() -> {
            requesting = false;
            try {
                if (sessions.get(request.peer.id()) != request.session) return;
                if (failure != null) throw new IllegalStateException(reason(failure));
                engine.validateRequest(request.peer.id().toString(), request.songId);
                if (request.quote.amount.signum() == 0) {
                    Entry added = engine.add(request.peer.id().toString(), request.peer.name(), resolved.track());
                    announceRequest(added);
                    result(request.peer, request.requestId, true, "Song added to the queue"); changed();
                } else {
                    String backend = economy.route(request.peer.id());
                    EconomyService.Payment payment = new EconomyService.Payment(request.quote.id, request.peer.id(), request.peer.name(), request.quote.amount, backend, System.currentTimeMillis());
                    purchase = new Purchase(payment, resolved.track(), request.requestId, request.quote.currency);
                    pay();
                }
            } catch (Exception e) { result(request.peer, request.requestId, false, reason(e)); }
            finally { nextRequest(); }
        }));
    }
    private void pay() {
        if (purchase == null || paying) return;
        paying = true;
        Purchase accepted = purchase;
        persist().whenComplete((ignored, error) -> submit(() -> {
            if (error != null) {
                paying = false; paymentRetryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                endpoint.notice(accepted.payment.playerId(), messages.translated("Could not save the song request; payment processing is paused. Retrying.", false));
            } else withdraw(accepted);
        }));
    }
    private void withdraw(Purchase accepted) {
        CompletionStage<EconomyService.Result> payment;
        try { payment = economy.charge(accepted.payment); }
        catch (Exception e) { payment = CompletableFuture.completedFuture(EconomyService.Result.pending()); }
        payment.whenComplete((receipt, failure) -> submit(() -> {
            paying = false;
            if (purchase != accepted) return;
            EconomyService.Result outcome = failure == null && receipt != null ? receipt : EconomyService.Result.pending();
            Peer player = new Peer(accepted.payment.playerId(), accepted.payment.playerName(), true, true, true, false);
            if (outcome.status() == EconomyService.Status.PENDING || outcome.status() == EconomyService.Status.UNKNOWN) {
                paymentRetryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String text = outcome.message() + " [" + accepted.payment.id() + "]";
                if (!text.equals(paymentNotice)) {
                    paymentNotice = text;
                    JsonObject body = new JsonObject(); body.addProperty("ok", false); body.addProperty("message", text); body.addProperty("pending", true);
                    send(player.id(), "result", accepted.requestId, body);
                    Component status = messages.render("payment.pending", "reason", messages.translated(outcome.message(), false), "transaction", accepted.payment.id());
                    endpoint.notice(player.id(), status);
                    tell(CONSOLE, "payment.console", "status", status, "player", player.id(), "backend", accepted.payment.backend());
                }
                return;
            }
            purchase = null; paymentNotice = "";
            if (outcome.status() == EconomyService.Status.PAID) {
                long before = engine.revision();
                Entry added = engine.addAccepted(accepted.payment.id(), player.id().toString(), player.name(), accepted.track);
                if (engine.revision() != before) announceRequest(added);
                String text = "Song added to the queue; paid " + accepted.payment.amount().toPlainString() + " " + accepted.currency;
                Component paid = messages.render("payment.paid", "amount", accepted.payment.amount().toPlainString(), "currency", accepted.currency);
                result(player, accepted.requestId, true, text, paid);
                if (sessions.containsKey(player.id()) && !accepted.requestId.startsWith("cmd:")) endpoint.notice(player.id(), paid);
            } else result(player, accepted.requestId, false, outcome.message());
            changed(); nextRequest();
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
    private void startChatSearch(Peer peer, String query) {
        if (query.isBlank() || query.length() > 120) throw messages.failure("search.invalid");
        ChatSearch previous = chatSearches.get(peer.id());
        if (previous != null && previous.loading) throw messages.failure("search.busy");
        ChatSearch search = new ChatSearch(query);
        chatSearches.put(peer.id(), search);
        loadChatSearch(peer, search, 0);
    }
    private void chatSearchPage(Peer peer, int page, String expectedId) {
        ChatSearch search = chatSearches.get(peer.id());
        if (search == null || System.nanoTime() >= search.expires || !expectedId.isEmpty() && !search.id.equals(expectedId))
            throw messages.failure("search.expired");
        if (search.loading) throw messages.failure("search.busy");
        if (page < 0 || page > 100 || page > search.page + 1 || page == search.page + 1 && !search.hasNext)
            throw messages.failure("search.invalid-page");
        loadChatSearch(peer, search, page);
    }
    private void loadChatSearch(Peer peer, ChatSearch search, int page) {
        search.loading = true;
        search.expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        tell(peer.id(), "search.loading", "query", search.query, "page", page + 1);
        CompletableFuture.supplyAsync(() -> {
            try { return provider.search(search.query, page, MusicChat.SEARCH_PAGE_SIZE); }
            catch (Exception e) { throw new CompletionException(e); }
        }, io).whenComplete((found, error) -> submit(() -> {
            if (chatSearches.get(peer.id()) != search) return;
            search.loading = false;
            if (error != null) {
                endpoint.chat(peer.id(), List.of(messages.render("search.failed", "reason", messages.translated(reason(error), false))));
                return;
            }
            if (page > 0 && found.tracks().isEmpty()) {
                if (page == search.page + 1) search.hasNext = false;
                endpoint.chat(peer.id(), List.of(messages.render("search.no-more")));
                return;
            }
            search.page = page; search.hasNext = found.hasNext() && page < 100;
            endpoint.chat(peer.id(), MusicChat.search(messages, peer, search.query, search.id, page, found.tracks(), search.hasNext));
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
                sessions.keySet().forEach(id -> tell(id, "audio.failed", "song", current.track.name, "reason", messages.translated(reason(failure), false)));
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
    private void broadcast() {
        commandState = new MusicCommands.Snapshot(engine.queue().stream().map(e -> new MusicCommands.QueueItem(e.entryId, e.requesterId)).toList(), engine.instance());
        for (UUID id : new ArrayList<>(sessions.keySet())) snapshot(id, "", 0);
        lastBroadcastRevision = engine.revision();
    }
    private void changed() { pump(); broadcast(); persist(); }
    private void result(Peer peer, String rid, boolean ok, String text) {
        result(peer, rid, ok, text, messages.translated(text, ok));
    }
    private void result(Peer peer, String rid, boolean ok, String text, Component chat) {
        Session session = sessions.get(peer.id());
        JsonObject body = new JsonObject(); body.addProperty("ok", ok); body.addProperty("message", text);
        if (session != null) { session.pending.remove(rid); remember(session, rid, body); send(peer.id(), "result", rid, body); }
        if (rid.startsWith("cmd:") || session == null) if (!text.isEmpty()) endpoint.notice(peer.id(), chat);
    }
    private void remember(Session session, String rid, JsonObject result) {
        session.results.put(rid, result);
        while (session.results.size() > 128) session.results.remove(session.results.keySet().iterator().next());
    }
    private void send(UUID id, String type, String rid, JsonObject body) {
        body.addProperty("serverId", config.serverId());
        if (!closed.get()) endpoint.send(id, MusicProtocol.frames(MusicProtocol.message(type, rid, body)));
    }
    public List<String> suggest(Peer peer, String[] args, MusicCommands.Mode mode) {
        return closed.get() ? List.of() : MusicCommands.suggest(peer, mode, args, commandState);
    }
    public void command(Peer peer, String[] args) { command(peer, args, MusicCommands.Mode.FOLIA); }
    public void command(Peer peer, String[] args, MusicCommands.Mode mode) {
        submit(() -> {
            String cmd = MusicCommands.name(args);
            String rid = "cmd:" + UUID.randomUUID();
            try {
                if (cmd.equals("payment") && mode == MusicCommands.Mode.VELOCITY)
                    throw messages.failure("command.backend");
                if (!MusicCommands.known(cmd, mode)) throw messages.failure("command.unknown");
                if (!MusicCommands.allowed(peer, cmd, mode)) throw messages.failure("command.denied");
                switch (cmd) {
                    case "join" -> {
                        if (args.length != 1) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        joinFromChat(peer);
                    }
                    case "help" -> {
                        if (args.length > 2) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        endpoint.chat(peer.id(), MusicCommands.help(messages, peer, mode, args.length == 2 ? args[1] : ""));
                    }
                    case "reload" -> {
                        if (args.length != 1) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        reload(peer);
                    }
                    case "queue" -> {
                        if (args.length > 2) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        int page = args.length == 2 ? Integer.parseInt(args[1]) - 1 : 0;
                        int pages = Math.max(1, (engine.queue().size() + PAGE_SIZE - 1) / PAGE_SIZE);
                        if (page < 0 || page >= pages) throw messages.failure("queue.invalid-page", "pages", pages);
                        tell(peer.id(), engine.current() == null ? "queue.empty" : "queue.current", "song", engine.current() == null ? "" : engine.current().track.name);
                        tell(peer.id(), "queue.heading", "page", page + 1, "pages", pages, "count", engine.queue().size());
                        engine.queue().stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).forEach(e -> tell(peer.id(), "queue.row", "entry", e.entryId, "song", e.track.name, "player", e.requesterName));
                    }
                    case "request" -> { if (args.length != 2) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd)); quote(peer, rid, Long.parseLong(args[1])); }
                    case "withdraw" -> { if (args.length != 2) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd)); engine.remove(peer.id().toString(), args[1], false); changed(); result(peer, rid, true, messages.text("queue.withdrawn"), messages.render("queue.withdrawn")); }
                    case "vote" -> {
                        if (args.length > 2) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        vote(peer, rid, args.length == 2 ? args[1] : "");
                    }
                    case "search" -> {
                        if (args.length < 2) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        startChatSearch(peer, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    }
                    case "searchpage" -> {
                        if (args.length < 2 || args.length > 3) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        chatSearchPage(peer, Integer.parseInt(args[1]) - 1, args.length == 3 ? args[2] : "");
                    }
                    case "skip", "pause", "resume", "clear", "remove" -> {
                        if (args.length != (cmd.equals("remove") ? 2 : 1)) throw messages.failure("command.usage", "usage", MusicCommands.usage(messages, cmd));
                        switch (cmd) {
                            case "skip" -> {
                                if (engine.current() != null) tellListeners("admin.skipped", "song", engine.current().track.name);
                                engine.skip(); audio = null;
                            }
                            case "pause" -> engine.pause(true);
                            case "resume" -> engine.pause(false);
                            case "clear" -> { engine.clear(); audio = null; }
                            case "remove" -> engine.remove(peer.id().toString(), args[1], true);
                        }
                        changed(); tell(peer.id(), "admin.updated");
                    }
                    default -> tell(peer.id(), "command.backend");
                }
            } catch (NumberFormatException e) {
                Component content = messages.render("command.number", "usage", MusicCommands.usage(messages, cmd));
                result(peer, rid, false, MusicChat.plain(content), content);
            } catch (MusicMessages.Failure e) { result(peer, rid, false, e.getMessage(), e.render(messages)); }
            catch (Exception e) { result(peer, rid, false, reason(e)); }
        });
    }
    private record Reload(ServerConfig config, MusicMessages messages) {}
    private void reload(Peer peer) {
        if (reloading) { tell(peer.id(), "reload.busy"); return; }
        reloading = true;
        ServerConfig previous = config;
        tell(peer.id(), "reload.loading");
        CompletableFuture.supplyAsync(() -> {
            try {
                MusicMessages language = MusicMessages.load(stateFile.getParent());
                return new Reload(ServerConfig.load(stateFile.getParent(), previous), language);
            }
            catch (Exception e) { throw new CompletionException(e); }
        }, disk).whenComplete((loaded, error) -> submit(() -> {
            reloading = false;
            if (error != null) { tell(peer.id(), "reload.failed", "reason", reason(error)); return; }
            ServerConfig next = loaded.config;
            messages = loaded.messages;
            config = next;
            engine.configure(next.playerLimit(), next.queueLimit(), next.cooldownSeconds());
            boolean priceChanged = previous.requestFeeEnabled() != next.requestFeeEnabled()
                    || previous.requestFeeAmount().compareTo(next.requestFeeAmount()) != 0 || !previous.currencyName().equals(next.currencyName());
            for (Session session : sessions.values()) {
                if (priceChanged && session.quote != null) {
                    session.quote = null;
                    tell(session.peer.id(), "reload.pricing");
                }
                welcome(session.peer, "");
            }
            changed();
            tell(peer.id(), "reload.success");
        }));
    }
    private CompletionStage<Void> persist() {
        JsonObject state = engine.save(); state.add("pendingPurchase", GSON.toJsonTree(purchase));
        String data = GSON.toJson(state);
        CompletableFuture<Void> saved = new CompletableFuture<>();
        if (!disk.isShutdown()) disk.execute(() -> {
            try {
                StateFiles.write(stateFile, data); saved.complete(null);
            } catch (Exception e) { saved.completeExceptionally(e); endpoint.log("Could not save the music queue", e); }
        });
        else saved.completeExceptionally(new IllegalStateException("The music server is stopping"));
        return saved;
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
            persist(); sessions.clear(); chatSearches.clear(); requests.clear();
            disk.execute(() -> termination.complete(null)); disk.shutdown();
        });
        owner.shutdown(); provider.close(); io.shutdownNow(); decoders.clear();
    }
    public CompletionStage<Void> termination() { return termination; }
}
