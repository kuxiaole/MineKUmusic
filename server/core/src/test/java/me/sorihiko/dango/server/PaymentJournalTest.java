package me.sorihiko.dango.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static me.sorihiko.dango.server.EconomyService.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentJournalTest {
    @TempDir Path directory;
    private Payment payment() { return new Payment(UUID.randomUUID().toString(), UUID.randomUUID(), "Synthetic payer", new BigDecimal("10.25"), "fixture-backend", System.currentTimeMillis()); }
    private Result result(CompletionStage<Result> future) throws Exception { return future.toCompletableFuture().get(5, TimeUnit.SECONDS); }
    private void stop(PaymentJournal journal) throws Exception { journal.close(); journal.termination().toCompletableFuture().get(5, TimeUnit.SECONDS); }

    @Test void concurrentRetriesAndRestartUseOneWithdrawal() throws Exception {
        AtomicInteger calls = new AtomicInteger(); Payment payment = payment();
        PaymentJournal journal = new PaymentJournal(directory); CompletableFuture<Result> response = new CompletableFuture<>();
        var first = journal.charge(payment, p -> { calls.incrementAndGet(); return response; });
        var retry = journal.charge(payment, p -> { calls.incrementAndGet(); return response; });
        response.complete(Result.paid()); assertEquals(Status.PAID, result(first).status()); assertEquals(Status.PAID, result(retry).status()); stop(journal);
        PaymentJournal restarted = new PaymentJournal(directory);
        assertEquals(Status.PAID, result(restarted.charge(payment, p -> { calls.incrementAndGet(); return response; })).status());
        assertEquals(1, calls.get()); stop(restarted);
    }
    @Test void interruptedWithdrawalNeedsReviewInsteadOfChargingAgain() throws Exception {
        Payment payment = payment(); PaymentJournal journal = new PaymentJournal(directory); CountDownLatch started = new CountDownLatch(1);
        journal.charge(payment, p -> { started.countDown(); return new CompletableFuture<>(); });
        assertTrue(started.await(5, TimeUnit.SECONDS)); stop(journal);
        AtomicInteger repeated = new AtomicInteger(); PaymentJournal restarted = new PaymentJournal(directory);
        assertEquals(Status.UNKNOWN, result(restarted.charge(payment, p -> { repeated.incrementAndGet(); return CompletableFuture.completedFuture(Result.paid()); })).status());
        assertEquals(Status.PAID, result(restarted.reconcile(payment.id(), true)).status());
        assertEquals(Status.PAID, result(restarted.charge(payment, p -> { repeated.incrementAndGet(); return CompletableFuture.completedFuture(Result.paid()); })).status());
        assertEquals(0, repeated.get()); stop(restarted);
    }
    @Test void knownRejectionIsRemembered() throws Exception {
        Payment payment = payment(); PaymentJournal journal = new PaymentJournal(directory); AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) assertEquals(Status.REJECTED, result(journal.charge(payment, p -> {
            calls.incrementAndGet(); return CompletableFuture.completedFuture(Result.rejected("Synthetic insufficient balance"));
        })).status());
        assertEquals(1, calls.get()); stop(journal);
    }
    @Test void expiredUndeliveredPaymentDoesNotWithdraw() throws Exception {
        Payment fresh = payment(); Payment expired = new Payment(fresh.id(), fresh.playerId(), fresh.playerName(), fresh.amount(), fresh.backend(), System.currentTimeMillis() - 180000);
        PaymentJournal journal = new PaymentJournal(directory); AtomicInteger calls = new AtomicInteger();
        assertEquals(Status.REJECTED, result(journal.charge(expired, p -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(Result.paid()); })).status());
        assertEquals(0, calls.get()); stop(journal);
    }
    @Test void bridgePreservesPaymentAndReceiptAcrossSerialization() {
        EconomyWire wire = new EconomyWire("synthetic-test-bridge-secret-not-a-real-credential");
        EconomyWire.Message request = new EconomyWire.Message("charge", payment(), null);
        assertEquals(request, wire.decode(wire.encode(request)));
        EconomyWire.Message response = new EconomyWire.Message("receipt", request.payment(), Result.paid());
        assertEquals(response, wire.decode(wire.encode(response)));
    }
}
