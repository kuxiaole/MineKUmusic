package me.sorihiko.dango.server;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static me.sorihiko.dango.protocol.MusicProtocol.GSON;
import static me.sorihiko.dango.server.EconomyService.*;

/** Vault and a local file cannot commit atomically. An interrupted withdrawal is kept for manual reconciliation. */
public final class PaymentJournal implements AutoCloseable {
    private record Record(Payment payment, Result result) {}
    private final Path file;
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<Result>> running = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "MineKU-Payments"));
    private volatile boolean closed;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();

    public PaymentJournal(Path directory) throws Exception {
        file = directory.resolve("payments.json");
        if (Files.exists(file)) for (JsonElement item : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
            Record record = GSON.fromJson(item, Record.class);
            records.put(record.payment.id(), record);
        }
    }
    public CompletionStage<Result> charge(Payment payment, Function<Payment, CompletionStage<Result>> withdraw) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        execute(() -> {
            Record previous = records.get(payment.id());
            if (previous != null) {
                if (!previous.payment.equals(payment)) { answer.complete(Result.rejected("Transaction details do not match")); return; }
                CompletableFuture<Result> active = running.get(payment.id());
                if (active != null) { active.thenAccept(answer::complete); return; }
                try { save(); answer.complete(previous.result == null ? Result.unknown() : previous.result); }
                catch (Exception e) { answer.complete(Result.unknown()); }
                return;
            }
            long now = System.currentTimeMillis();
            if (payment.createdAt() > now + 30000 || now - payment.createdAt() > 120000) {
                answer.complete(Result.rejected("The payment expired before it could be processed")); return;
            }
            records.values().removeIf(r -> r.result != null && r.result.status() != Status.UNKNOWN && now - r.payment.createdAt() > TimeUnit.DAYS.toMillis(30));
            if (records.size() >= 50000) { answer.complete(Result.rejected("The payment journal needs administrator maintenance")); return; }
            records.put(payment.id(), new Record(payment, null));
            try { save(); }
            catch (Exception e) {
                records.remove(payment.id()); answer.complete(Result.rejected("Could not save the payment record; no payment was made")); return;
            }
            running.put(payment.id(), answer);
            try {
                withdraw.apply(payment).whenComplete((result, failure) -> execute(() -> {
                    Result settled = failure == null && result != null ? result : Result.unknown();
                    records.put(payment.id(), new Record(payment, settled));
                    running.remove(payment.id());
                    try { save(); answer.complete(settled); }
                    catch (Exception e) { answer.complete(Result.unknown()); }
                }, answer));
            } catch (Exception e) {
                running.remove(payment.id()); answer.complete(Result.unknown());
            }
        }, answer);
        return answer;
    }
    public CompletionStage<Result> reconcile(String id, boolean paid) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        execute(() -> {
            Record record = records.get(id);
            if (record == null || running.containsKey(id) || record.result != null && record.result.status() != Status.UNKNOWN) {
                answer.complete(Result.rejected("Only an interrupted transaction can be reconciled")); return;
            }
            Result result = paid ? Result.paid() : Result.rejected("Payment cancelled after administrator review");
            records.put(id, new Record(record.payment, result));
            try { save(); answer.complete(result); }
            catch (Exception e) { answer.complete(Result.unknown()); }
        }, answer);
        return answer;
    }
    private void save() throws Exception { StateFiles.write(file, GSON.toJson(records.values())); }
    private void execute(Runnable task, CompletableFuture<Result> answer) {
        if (closed) { answer.complete(Result.pending()); return; }
        try { worker.execute(task); } catch (RejectedExecutionException e) { answer.complete(Result.pending()); }
    }
    public CompletionStage<Void> termination() { return termination; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.execute(() -> { running.values().forEach(f -> f.complete(Result.pending())); running.clear(); termination.complete(null); });
        worker.shutdown();
    }
}
