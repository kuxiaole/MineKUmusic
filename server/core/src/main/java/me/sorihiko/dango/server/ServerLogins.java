package me.sorihiko.dango.server;

import com.google.gson.JsonObject;
import me.sorihiko.dango.music.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Only image/status leave this coordinator. Blocking authentication and disk writes use its workers. */
final class ServerLogins implements AutoCloseable {
    private final Path directory;
    private final Consumer<Runnable> dispatch;
    private final BiConsumer<UUID, JsonObject> sink;
    private final Function<String, ProviderQrLogin> factory;
    record Request(UUID player, String name, String source, boolean shared, String generation) {}
    interface Committer { CompletionStage<Void> accept(Request request, NativeAccount account, BooleanSupplier beginCommit); }
    private final Committer committer;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), r -> new Thread(r, "MineKUmusic-Login"), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> started = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final class Job {
        final UUID player;
        final String source, id = UUID.randomUUID().toString();
        final ProviderQrLogin login;
        final Request request;
        final long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        // 0 running, 1 committing a verified account, 2 terminal. Cancellation can only win before commit.
        final AtomicInteger phase = new AtomicInteger();
        JsonObject last;
        Job(Request request, ProviderQrLogin login) { this.request = request; this.player = request.player; this.source = request.source; this.login = login; }
    }
    ServerLogins(Path directory, Consumer<Runnable> dispatch, BiConsumer<UUID, JsonObject> sink) {
        this(directory, dispatch, sink, source -> source.equals("qq") ? new QqQrLogin() : new NeteaseQrLogin());
    }
    ServerLogins(Path directory, Consumer<Runnable> dispatch, BiConsumer<UUID, JsonObject> sink, Function<String, ProviderQrLogin> factory) {
        this(directory, dispatch, sink, factory, (request, account, begin) -> {
            try {
                if (!begin.getAsBoolean()) throw new CancellationException();
                ServerCookies.save(directory, request.source, account.getCookies());
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) { return CompletableFuture.failedFuture(e); }
        });
    }
    ServerLogins(Path directory, Consumer<Runnable> dispatch, BiConsumer<UUID, JsonObject> sink, Committer committer) {
        this(directory, dispatch, sink, source -> source.equals("qq") ? new QqQrLogin() : new NeteaseQrLogin(), committer);
    }
    ServerLogins(Path directory, Consumer<Runnable> dispatch, BiConsumer<UUID, JsonObject> sink, Function<String, ProviderQrLogin> factory, Committer committer) {
        this.directory = directory; this.dispatch = dispatch; this.sink = sink; this.factory = factory;
        this.committer = committer;
    }
    void start(UUID player, String source) {
        start(new Request(player, "", source, false, ""));
    }
    void start(Request request) {
        UUID player = request.player; String source = request.source;
        ServerCookies.path(directory, source);
        Job previous = jobs.get(source);
        if (previous != null && previous.phase.get() < 2) throw new QueueEngine.RuleException("A login for this source is already in progress");
        if (jobs.values().stream().anyMatch(job -> job.player.equals(player) && job.phase.get() < 2))
            throw new QueueEngine.RuleException("Close your current server login before starting another");
        long now = System.nanoTime();
        if (now - started.getOrDefault(player, now - TimeUnit.SECONDS.toNanos(30)) < TimeUnit.SECONDS.toNanos(30))
            throw new QueueEngine.RuleException("Please wait 30 seconds before starting another music login.");
        Job job = new Job(request, factory.apply(source));
        started.put(player, now);
        jobs.put(source, job); publish(job, "loading", null);
        try { workers.execute(() -> run(job)); }
        catch (RejectedExecutionException e) { job.phase.set(2); publish(job, "error", null); }
    }
    private void run(Job job) {
        try {
            NativeAccount account = job.login.run(new ProviderQrLogin.Observer() {
                @Override public boolean active() { return !closed.get() && job.phase.get() == 0 && System.nanoTime() < job.expires; }
                @Override public void image(byte[] png) {
                    if (png.length == 0 || png.length > 384 * 1024) throw new IllegalArgumentException("Invalid QR image");
                    if (active()) publish(job, "waiting", Base64.getEncoder().encodeToString(png));
                }
                @Override public void state(String state) { if (active() && Set.of("waiting", "scanned", "verifying").contains(state)) publish(job, state, null); }
            });
            if (!active(job)) return;
            if (!account.getSource().getKey().equals(job.source)) throw new IllegalStateException("Wrong login source");
            publish(job, "verifying", null);
            committer.accept(job.request, account, () -> active(job) && job.phase.compareAndSet(0, 1))
                    .whenComplete((ignored, failure) -> {
                        if (job.phase.getAndSet(2) == 2) return;
                        Throwable cause = failure;
                        while (cause instanceof CompletionException || cause instanceof ExecutionException) cause = cause.getCause();
                        publish(job, cause == null ? "success" : cause instanceof ServerAccounts.Failure f ? f.state : "error", "");
                    });
        } catch (CancellationException e) {
            if (job.phase.compareAndSet(0, 2)) publish(job, "expired", "");
        } catch (Exception e) {
            if (job.phase.getAndSet(2) != 2) publish(job, "error", "");
        } finally { try { job.login.close(); } catch (Exception ignored) { } }
    }
    private boolean active(Job job) { return !closed.get() && job.phase.get() == 0 && System.nanoTime() < job.expires; }
    boolean busy(String source) { Job job = jobs.get(source); return job != null && job.phase.get() < 2; }
    void cancelSource(String source) { Job job = jobs.get(source); if (job != null) cancel(job); }
    private void publish(Job job, String state, String image) {
        dispatch.accept(() -> {
            if (closed.get() || jobs.get(job.source) != job) return;
            if (job.phase.get() == 2 && Set.of("loading", "waiting", "scanned", "verifying").contains(state)) return;
            JsonObject body = new JsonObject();
            body.addProperty("sessionId", job.id); body.addProperty("source", job.source); body.addProperty("state", state);
            body.addProperty("shared", job.request.shared);
            body.addProperty("expiresInMs", Math.max(0, TimeUnit.NANOSECONDS.toMillis(job.expires - System.nanoTime())));
            String previousImage = job.last != null && job.last.has("image") ? job.last.get("image").getAsString() : "";
            body.addProperty("image", image == null ? previousImage : image);
            job.last = body; sink.accept(job.player, body);
        });
    }
    void resend(UUID player) { jobs.values().stream().filter(job -> job.player.equals(player) && job.last != null).forEach(job -> sink.accept(player, job.last)); }
    void cancel(UUID player, String source, String session) {
        for (Job job : jobs.values()) if (job.player.equals(player) && (source.isEmpty() || job.source.equals(source)) && (session.isEmpty() || job.id.equals(session))) cancel(job);
    }
    private void cancel(Job job) {
        if (job.phase.compareAndSet(0, 2)) {
            try { job.login.close(); } catch (Exception ignored) { }
            publish(job, "cancelled", "");
        }
    }
    void expire() {
        started.values().removeIf(at -> System.nanoTime() - at >= TimeUnit.SECONDS.toNanos(30));
        jobs.values().forEach(job -> {
            if (System.nanoTime() >= job.expires && job.phase.compareAndSet(0, 2)) {
                try { job.login.close(); } catch (Exception ignored) { }
                publish(job, "expired", "");
            }
        });
        jobs.values().removeIf(job -> job.phase.get() == 2 && System.nanoTime() - job.expires > TimeUnit.MINUTES.toNanos(1));
    }
    void cancelAll() { jobs.values().forEach(this::cancel); }
    @Override public void close() { closed.set(true); cancelAll(); workers.shutdownNow(); jobs.clear(); }
}
