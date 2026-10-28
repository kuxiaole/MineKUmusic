package me.sorihiko.dango.server;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;

public interface EconomyService extends AutoCloseable {
    record Payment(String id, UUID playerId, String playerName, BigDecimal amount, String backend, long createdAt) {}
    enum Status { PAID, REJECTED, PENDING, UNKNOWN }
    record Result(Status status, String message) {
        public static Result paid() { return new Result(Status.PAID, "Payment completed"); }
        public static Result rejected(String message) { return new Result(Status.REJECTED, message); }
        public static Result pending() { return new Result(Status.PENDING, "Payment is being checked; do not request this song again"); }
        public static Result pending(String message) { return new Result(Status.PENDING, message); }
        public static Result unknown() { return new Result(Status.UNKNOWN, "Payment requires an administrator to check the transaction record"); }
    }
    default String route(UUID player) { return "local"; }
    CompletionStage<Result> charge(Payment payment);
    @Override default void close() {}
    EconomyService UNAVAILABLE = new EconomyService() {
        @Override public String route(UUID player) { throw new IllegalStateException("The server economy is unavailable"); }
        @Override public CompletionStage<Result> charge(Payment payment) {
            return CompletableFuture.completedFuture(Result.pending("The economy bridge is unavailable; check the server configuration"));
        }
    };
}
