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
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), r -> new Thread(r, "MineKUmusic-Login"), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final class Job {
        final UUID player;
        final String source, id = UUID.randomUUID().toString();
        final ProviderQrLogin login;
        final long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        // 0 running, 1 committing a verified account, 2 terminal. Cancellation can only win before commit.
        final AtomicInteger phase = new AtomicInteger();
        JsonObject last;
        Job(UUID player, String source, ProviderQrLogin login) { this.player = player; this.source = source; this.login = login; }
    }
    ServerLogins(Path directory, Consumer<Runnable> dispatch, BiConsumer<UUID, JsonObject> sink) {
        this(directory, dispatch, sink, source -> source.equals("qq") ? new QqQrLogin() : new NeteaseQrLogin());
    }
    ServerLogins(Path directory, Consumer<Runnable> dispatch, BiConsumer<UUID, JsonObject> sink, Function<String, ProviderQrLogin> factory) {
        this.directory = directory; this.dispatch = dispatch; this.sink = sink; this.factory = factory;
    }
    void start(UUID player, String source) {
        ServerCookies.path(directory, source);
        Job previous = jobs.get(source);
        if (previous != null && previous.phase.get() < 2) throw new QueueEngine.RuleException("A login for this source is already in progress");
        if (jobs.values().stream().anyMatch(job -> job.player.equals(player) && job.phase.get() < 2))
            throw new QueueEngine.RuleException("Close your current server login before starting another");
        Job job = new Job(player, source, factory.apply(source));
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
            if (closed.get() || System.nanoTime() >= job.expires || !job.phase.compareAndSet(0, 1)) return;
            if (!account.getSource().getKey().equals(job.source)) throw new IllegalStateException("Wrong login source");
            ServerCookies.save(directory, job.source, account.getCookies());
            job.phase.set(2); publish(job, "success", "");
        } catch (CancellationException e) {
            if (job.phase.compareAndSet(0, 2)) publish(job, "expired", "");
        } catch (Exception e) {
            if (job.phase.getAndSet(2) != 2) publish(job, "error", "");
        } finally { try { job.login.close(); } catch (Exception ignored) { } }
    }
    private void publish(Job job, String state, String image) {
        dispatch.accept(() -> {
            if (closed.get() || jobs.get(job.source) != job) return;
            if (job.phase.get() == 2 && Set.of("loading", "waiting", "scanned", "verifying").contains(state)) return;
            JsonObject body = new JsonObject();
            body.addProperty("sessionId", job.id); body.addProperty("source", job.source); body.addProperty("state", state);
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
