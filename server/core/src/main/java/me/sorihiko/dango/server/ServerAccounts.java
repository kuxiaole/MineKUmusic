package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.music.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import static me.sorihiko.dango.protocol.MusicProtocol.GSON;

/** Mutable slots belong to MusicServer's queue executor; workers only exchange immutable results. */
final class ServerAccounts implements AutoCloseable {
    static final long CHECK_MS = 60_000, GRACE_MS = 300_000;
    record Contribution(String fingerprint, String generation, String playerId, String playerName,
                        String userId, long expiresAt, long verifiedAt) {}
    private record Read(String fingerprint, Contribution contribution, NativeAccount account) {}
    private record Probe(Read read, AccountMembership.Result result) {}
    private static final class Slot {
        final String source;
        String state = "checking", fingerprint = "", generation = "";
        Contribution contribution;
        long nextCheck;
        boolean writing;
        CompletableFuture<Void> checking;
        Slot(String source) { this.source = source; }
    }
    static final class Failure extends IllegalStateException {
        final String state;
        Failure(String state, String message) { super(message); this.state = state; }
    }
    private final Path directory;
    private final Executor io, disk;
    private final Consumer<Runnable> dispatch;
    private final Runnable changed;
    private final Function<NativeAccount, AccountMembership.Result> verifier;
    private final LongSupplier clock;
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    ServerAccounts(Path directory, Executor io, Executor disk, Consumer<Runnable> dispatch, Runnable changed) {
        this(directory, io, disk, dispatch, changed, new AccountMembership()::check, System::currentTimeMillis);
    }
    ServerAccounts(Path directory, Executor io, Executor disk, Consumer<Runnable> dispatch, Runnable changed,
                   Function<NativeAccount, AccountMembership.Result> verifier, LongSupplier clock) {
        this.directory = directory; this.io = io; this.disk = disk; this.dispatch = dispatch;
        this.changed = changed; this.verifier = verifier; this.clock = clock;
        slots.put("netease", new Slot("netease")); slots.put("qq", new Slot("qq"));
    }
    private Slot slot(String source) {
        Slot slot = slots.get(source);
        if (slot == null) throw new IllegalArgumentException("Unsupported music source or song ID");
        return slot;
    }
    String generation(String source) { return slot(source).generation; }
    boolean canContribute(String source) {
        Slot slot = slot(source);
        return !slot.writing && !state(slot).equals("valid") && !state(slot).equals("checking");
    }
    boolean contributor(UUID player, String source) {
        Contribution c = slot(source).contribution;
        return c != null && player.toString().equals(c.playerId);
    }
    String discount(UUID player, String source) {
        Slot slot = slot(source); Contribution c = slot.contribution; long now = clock.getAsLong();
        if (slot.writing || c == null || !player.toString().equals(c.playerId) || c.verifiedAt <= 0 || now >= c.expiresAt) return "";
        return slot.state.equals("valid") || slot.state.equals("unavailable") && now < c.verifiedAt + GRACE_MS ? slot.generation : "";
    }
    private String state(Slot slot) {
        if (slot.state.equals("valid") && slot.contribution != null && clock.getAsLong() >= slot.contribution.expiresAt) return "nonMember";
        return slot.state;
    }
    JsonArray snapshot(UUID viewer, boolean admin) {
        JsonArray accounts = new JsonArray();
        for (Slot slot : slots.values()) {
            Contribution c = slot.contribution;
            JsonObject row = new JsonObject();
            row.addProperty("source", slot.source); row.addProperty("generation", slot.generation); row.addProperty("state", state(slot));
            row.addProperty("contributorName", c == null ? "" : c.playerName);
            row.addProperty("ownContribution", contributor(viewer, slot.source));
            row.addProperty("canShare", canContribute(slot.source));
            row.addProperty("canRemove", !slot.writing && !slot.fingerprint.isEmpty() && !slot.state.equals("missing") && (admin || contributor(viewer, slot.source)));
            row.addProperty("free", !discount(viewer, slot.source).isEmpty());
            row.addProperty("graceRemainingMs", c != null && slot.state.equals("unavailable")
                    ? Math.max(0, Math.min(c.expiresAt, c.verifiedAt + GRACE_MS) - clock.getAsLong()) : 0);
            row.addProperty("busy", slot.writing); accounts.add(row);
        }
        return accounts;
    }
    void tick() {
        if (closed.get()) return;
        for (Slot slot : slots.values()) if (!slot.writing && clock.getAsLong() >= slot.nextCheck) check(slot.source);
    }
    CompletionStage<Void> check(String source) {
        Slot slot = slot(source);
        if (closed.get()) return CompletableFuture.failedFuture(new CancellationException());
        if (slot.checking != null) return slot.checking;
        if (slot.writing) return CompletableFuture.failedFuture(new Failure("changed", "The music account is changing. Please retry."));
        CompletableFuture<Void> future = new CompletableFuture<>(); slot.checking = future;
        String generation = slot.generation;
        CompletableFuture.supplyAsync(() -> {
            try {
                Read read = read(source);
                AccountMembership.Result result;
                try { result = read.account == null ? AccountMembership.Result.invalid() : verifier.apply(read.account); }
                catch (Exception ignored) { result = AccountMembership.Result.temporary(); }
                // A manual Cookie replacement during a remote check cannot inherit the previous account's proof.
                String current = fingerprint(source);
                if (!read.fingerprint.equals(current)) return new Probe(new Read(current, null, null), AccountMembership.Result.temporary());
                return new Probe(read, result);
            } catch (Exception ignored) {
                try { return new Probe(new Read(fingerprint(source), null, null), AccountMembership.Result.invalid()); }
                catch (Exception inaccessible) { return new Probe(null, AccountMembership.Result.temporary()); }
            }
        }, io).whenComplete((probe, error) -> dispatch.accept(() -> {
            if (slot.checking != future) return;
            if (closed.get() || slot.writing || !slot.generation.equals(generation)) { slot.checking = null; future.complete(null); return; }
            apply(slot, error == null ? probe : new Probe(null, AccountMembership.Result.temporary()));
            slot.nextCheck = clock.getAsLong() + CHECK_MS;
            Contribution saved = slot.contribution;
            String appliedGeneration = slot.generation;
            // The proof timestamp is persisted before a quote can use it; restarting never renews a grace period.
            CompletableFuture.runAsync(() -> {
                if (saved == null || closed.get()) return;
                try { if (saved.fingerprint.equals(fingerprint(source))) StateFiles.write(metadata(source), GSON.toJson(saved)); }
                catch (Exception e) { throw new CompletionException(e); }
            }, disk).whenComplete((ignored, saveError) -> dispatch.accept(() -> {
                if (saveError != null && slot.generation.equals(appliedGeneration) && !slot.writing) slot.state = "unavailable";
                if (slot.checking == future) slot.checking = null;
                changed.run(); future.complete(null);
            }));
        }));
        return future;
    }
    private void apply(Slot slot, Probe probe) {
        Read read = probe.read; AccountMembership.Result result = probe.result;
        if (read != null) {
            slot.fingerprint = read.fingerprint; slot.contribution = read.contribution;
            slot.generation = read.contribution == null ? read.fingerprint : read.contribution.generation;
            if (read.fingerprint.isEmpty() || read.account == null) {
                slot.state = !read.fingerprint.isEmpty() && result.status() == AccountMembership.Status.TEMPORARY ? "unavailable" : "missing";
                return;
            }
        }
        Contribution old = slot.contribution;
        if (old != null && !result.userId().isEmpty() && !old.userId.isEmpty() && !old.userId.equals(result.userId())) old = null;
        if (read != null && old == null) old = new Contribution(read.fingerprint, read.fingerprint, "", "", result.userId(), 0, 0);
        if (old != null) {
            long until = result.status() == AccountMembership.Status.TEMPORARY ? old.expiresAt : result.expiresAt();
            long verified = switch (result.status()) {
                case VALID -> clock.getAsLong();
                case TEMPORARY -> old.verifiedAt;
                default -> 0;
            };
            slot.contribution = new Contribution(old.fingerprint, old.generation, old.playerId, old.playerName,
                    result.userId().isEmpty() ? old.userId : result.userId(), until, verified);
            slot.generation = old.generation;
        }
        slot.state = switch (result.status()) {
            case VALID -> "valid"; case INVALID -> "invalid"; case NON_MEMBER -> "nonMember"; case TEMPORARY -> "unavailable";
        };
    }
    CompletionStage<Void> accept(ServerLogins.Request request, NativeAccount account, BooleanSupplier beginCommit) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> verifier.apply(account), io).whenComplete((membership, error) -> dispatch.accept(() -> {
            if (closed.get()) { future.completeExceptionally(new CancellationException()); return; }
            Slot slot = slot(request.source());
            if (error != null || membership == null) membershipFailure(future, "unavailable", "Membership verification is temporarily unavailable. Please retry.");
            else if (request.shared() && (membership.status() != AccountMembership.Status.VALID || membership.expiresAt() <= clock.getAsLong())) {
                if (membership.status() == AccountMembership.Status.NON_MEMBER || membership.status() == AccountMembership.Status.VALID) membershipFailure(future, "notMember", "An active music membership is required to share this account.");
                else membershipFailure(future, "unavailable", "Membership verification failed. Please retry.");
            } else if (slot.writing || !slot.generation.equals(request.generation()) || request.shared() && !canContribute(request.source())) {
                membershipFailure(future, "changed", "The server music account has changed. Please refresh.");
            } else if (!beginCommit.getAsBoolean()) future.completeExceptionally(new CancellationException());
            else {
                slot.writing = true; changed.run();
                String expected = slot.fingerprint;
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (closed.get() || !expected.equals(fingerprint(request.source()))) throw new CancellationException();
                        if (request.shared() && membership.expiresAt() <= clock.getAsLong()) throw new IllegalStateException("Membership expired before saving");
                        Path cookie = ServerCookies.path(directory, request.source()), meta = metadata(request.source());
                        byte[] previousCookie = Files.exists(cookie) ? Files.readAllBytes(cookie) : null;
                        byte[] previousMeta = Files.exists(meta) ? Files.readAllBytes(meta) : null;
                        try {
                            ServerCookies.save(directory, request.source(), account.getCookies());
                            Contribution saved = new Contribution(fingerprint(request.source()), UUID.randomUUID().toString(),
                                    request.shared() ? request.player().toString() : "", request.shared() ? request.name() : "",
                                    membership.userId(), membership.expiresAt(), membership.status() == AccountMembership.Status.VALID ? clock.getAsLong() : 0);
                            StateFiles.write(meta, GSON.toJson(saved)); return saved;
                        } catch (Exception failure) {
                            restore(cookie, previousCookie); restore(meta, previousMeta); throw failure;
                        }
                    } catch (Exception e) { throw new CompletionException(e); }
                }, disk).whenComplete((saved, failure) -> dispatch.accept(() -> {
                    slot.writing = false; slot.nextCheck = clock.getAsLong() + CHECK_MS;
                    if (failure == null) {
                        slot.contribution = saved; slot.fingerprint = saved.fingerprint; slot.generation = saved.generation;
                        slot.state = membership.status() == AccountMembership.Status.VALID ? "valid" : "nonMember";
                        future.complete(null);
                    } else { slot.state = "checking"; slot.nextCheck = 0; membershipFailure(future, "error", "Could not save the shared music account. Please retry."); }
                    changed.run();
                }));
            }
        }));
        return future;
    }
    CompletionStage<Void> remove(UUID player, boolean admin, String source, String generation) {
        Slot slot = slot(source);
        if (slot.writing || !slot.generation.equals(generation)) throw new Failure("changed", "The server music account has changed. Please refresh.");
        if (!admin && !contributor(player, source)) throw new IllegalArgumentException("You can only withdraw your own shared music account.");
        slot.writing = true; changed.run();
        String expected = slot.fingerprint; CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> {
            try {
                if (closed.get() || !expected.equals(fingerprint(source))) throw new CancellationException();
                // Ownership is revoked first, so even a partial disk failure cannot leave a free entitlement.
                Files.deleteIfExists(metadata(source));
                // An empty array also prevents legacy cookie.json migration from resurrecting a withdrawn login.
                StateFiles.write(ServerCookies.path(directory, source), "[]");
                return fingerprint(source);
            } catch (Exception e) { throw new CompletionException(e); }
        }, disk).whenComplete((fingerprint, error) -> dispatch.accept(() -> {
            slot.writing = false; slot.contribution = null; slot.generation = error == null ? fingerprint : ""; slot.fingerprint = slot.generation; slot.nextCheck = 0;
            slot.state = error == null ? "missing" : "checking";
            if (error == null) future.complete(null); else membershipFailure(future, "error", "Could not remove the music account. Please retry.");
            changed.run();
        }));
        return future;
    }
    private static void membershipFailure(CompletableFuture<Void> future, String state, String message) { future.completeExceptionally(new Failure(state, message)); }
    private Path metadata(String source) { return directory.resolve(source + "-contribution.json"); }
    private String fingerprint(String source) throws Exception {
        Path path = ServerCookies.path(directory, source);
        if (!Files.exists(path)) return "";
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IllegalArgumentException();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }
    private Read read(String source) throws Exception {
        if (source.equals("netease")) ServerCookies.migrate(directory);
        String fingerprint = fingerprint(source);
        if (fingerprint.isEmpty()) return new Read("", null, null);
        Contribution contribution = null;
        try {
            Path path = metadata(source);
            if (Files.isRegularFile(path) && Files.size(path) <= 16 * 1024) {
                Contribution c = GSON.fromJson(Files.readString(path), Contribution.class);
                if (c != null && fingerprint.equals(c.fingerprint) && c.generation != null && c.playerId != null && c.playerName != null && c.userId != null) contribution = c;
            }
        } catch (Exception ignored) { }
        var cookies = ServerCookies.load(directory, source);
        if (cookies.getCookieStore().getCookies().isEmpty()) return new Read(fingerprint, null, null);
        NativeAccount account;
        if (source.equals("qq")) {
            String user = CookieValues.cookie(cookies, "qqmusic_uin"), key = CookieValues.cookie(cookies, "qqmusic_key");
            int type = CookieValues.cookie(cookies, "login_type").equals("2") || key.startsWith("W_X") ? 1 : 2;
            account = new NativeAccount(MusicSource.QQ, user, "", "", "", cookies, type, "");
        } else account = new NativeAccount(MusicSource.NETEASE, "", "", "", "", cookies);
        if (!fingerprint.equals(fingerprint(source))) throw new IllegalStateException();
        return new Read(fingerprint, contribution, account);
    }
    private static void restore(Path path, byte[] previous) throws Exception {
        if (previous == null) Files.deleteIfExists(path);
        else StateFiles.write(path, new String(previous, java.nio.charset.StandardCharsets.UTF_8));
    }
    @Override public void close() { closed.set(true); }
}
