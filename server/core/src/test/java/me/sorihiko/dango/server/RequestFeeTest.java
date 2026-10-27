package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.protocol.MusicProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated synthetic balances and music; no real account or economy provider is used. */
class RequestFeeTest {
    @TempDir Path directory;
    private final List<JsonObject> messages = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final MusicServer.Peer player = new MusicServer.Peer(UUID.randomUUID(), "Synthetic payer", true, true, true, false);
    private final List<EconomyService.Payment> payments = new CopyOnWriteArrayList<>();
    private final Set<String> withdrawals = ConcurrentHashMap.newKeySet();
    private volatile EconomyService.Result receipt = EconomyService.Result.paid();
    private CompletableFuture<EconomyService.Result> delayed;
    private volatile String route = "fixture-backend-a";
    private MusicServer server;
    private final EconomyService economy = new EconomyService() {
        @Override public String route(UUID id) { return route; }
        @Override public CompletionStage<Result> charge(Payment payment) {
            payments.add(payment);
            if (receipt.status() != Status.REJECTED) withdrawals.add(payment.id());
            return delayed != null ? delayed : CompletableFuture.completedFuture(receipt);
        }
    };
    private final MusicProvider provider = new MusicProvider() {
        @Override public Search search(String query, int page) { return new Search(List.of(track(101)), false); }
        @Override public Audio resolve(long id) {
            if (id == 404) throw new IllegalArgumentException("Synthetic unavailable song");
            return new Audio(track(id), "https://example.invalid/synthetic-fee-test.mp3", "", "");
        }
    };
    private static MusicProtocol.Track track(long id) { return new MusicProtocol.Track(id, "Synthetic paid song " + id, List.of("Fixture artist"), 180000, ""); }
    private void start(boolean paid) throws Exception {
        MusicProtocol.Assembler assembler = new MusicProtocol.Assembler();
        var endpoint = new MusicServer.Endpoint() {
            @Override public void send(UUID id, List<byte[]> frames) {
                try { for (byte[] frame : frames) { JsonObject m = assembler.accept(frame); if (m != null) messages.add(m); } }
                catch (Exception e) { errors.add(e); }
            }
            @Override public void tell(UUID id, String text) {}
            @Override public void votePrompt(UUID id, String text, String command) {}
            @Override public void log(String text, Throwable error) { errors.add(error); }
        };
        server = new MusicServer(directory, new ServerConfig("synthetic-network", "Fixture", 3, 100, 0, 1,
                paid, new BigDecimal("12.50"), "KU\u5e01", false, ""), provider, endpoint, economy);
        send("hello", new JsonObject()); send("subscribe", new JsonObject());
        await(() -> !of("snapshot").isEmpty() && last("snapshot").get("subscribed").getAsBoolean());
    }
    @AfterEach void stop() throws Exception {
        if (server != null) { server.close(); server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        assertTrue(errors.isEmpty(), errors.toString());
    }
    private String send(String type, JsonObject body) {
        String rid = UUID.randomUUID().toString(); send(type, rid, body); return rid;
    }
    private void send(String type, String rid, JsonObject body) {
        for (byte[] bytes : MusicProtocol.frames(MusicProtocol.message(type, rid, body))) server.receive(player, bytes);
    }
    private List<JsonObject> of(String type) { return messages.stream().filter(m -> m.get("type").getAsString().equals(type)).toList(); }
    private JsonObject last(String type) { return of(type).getLast().getAsJsonObject("body"); }
    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Expected synthetic payment event was not received");
    }
    private String quote(long songId) throws Exception {
        int count = of("requestQuote").size(); JsonObject body = new JsonObject(); body.addProperty("songId", songId);
        send("quoteRequest", body); await(() -> of("requestQuote").size() > count);
        return last("requestQuote").get("quoteId").getAsString();
    }
    private JsonObject confirmation(String quote) { JsonObject body = new JsonObject(); body.addProperty("quoteId", quote); return body; }
    private void awaitResult(String rid) throws Exception { await(() -> of("result").stream().anyMatch(m -> m.get("requestId").getAsString().equals(rid))); }

    @Test void searchAndCancelledQuotesAreFreeAndConfirmedRequestsChargeOnce() throws Exception {
        start(true);
        JsonObject query = new JsonObject(); query.addProperty("query", "synthetic"); send("search", query);
        await(() -> !of("searchResults").isEmpty()); assertTrue(payments.isEmpty());
        String cancelled = quote(101);
        assertEquals("12.50", last("requestQuote").get("amount").getAsString());
        assertEquals("KU\u5e01", last("requestQuote").get("currencyName").getAsString());
        String cancel = send("cancelRequest", confirmation(cancelled)); awaitResult(cancel); assertTrue(payments.isEmpty());
        JsonObject confirm = confirmation(quote(101));
        String rid = send("confirmRequest", confirm); awaitResult(rid); await(() -> !of("audio").isEmpty());
        send("confirmRequest", rid, confirm);
        String ping = send("ping", new JsonObject()); await(() -> of("snapshot").stream().anyMatch(m -> m.get("requestId").getAsString().equals(ping)));
        assertEquals(1, payments.size()); assertEquals(new BigDecimal("12.50"), payments.getFirst().amount());
    }
    @Test void rejectedPaymentDoesNotQueueOrStartACooldown() throws Exception {
        start(true); receipt = EconomyService.Result.rejected("Synthetic insufficient balance");
        String first = send("confirmRequest", confirmation(quote(101))); awaitResult(first);
        assertTrue(of("audio").isEmpty()); assertTrue(withdrawals.isEmpty());
        receipt = EconomyService.Result.paid();
        send("confirmRequest", confirmation(quote(101))); await(() -> !of("audio").isEmpty());
        assertEquals(1, withdrawals.size());
    }
    @Test void unavailableSongNeverReachesTheEconomy() throws Exception {
        start(true); String rid = send("confirmRequest", confirmation(quote(404))); awaitResult(rid);
        assertFalse(last("result").get("ok").getAsBoolean()); assertTrue(payments.isEmpty());
    }
    @Test void duplicateQueuedSongIsRejectedBeforeAnotherPayment() throws Exception {
        start(true); send("confirmRequest", confirmation(quote(101))); await(() -> !of("audio").isEmpty());
        JsonObject body = new JsonObject(); body.addProperty("songId", 101);
        String rid = send("quoteRequest", body); awaitResult(rid);
        assertEquals(1, payments.size()); assertFalse(last("result").get("ok").getAsBoolean());
    }
    @Test void legacyClientGetsAnUpdateMessageWithoutBeingCharged() throws Exception {
        start(true); JsonObject body = new JsonObject(); body.addProperty("songId", 101);
        String rid = send("request", body); awaitResult(rid);
        assertTrue(payments.isEmpty()); assertTrue(last("result").get("message").getAsString().contains("Update MineKU Music"));
    }
    @Test void freeModeStillSupportsConfirmationWithoutAnEconomy() throws Exception {
        start(false); String quote = quote(101); assertEquals("0", last("requestQuote").get("amount").getAsString());
        send("confirmRequest", confirmation(quote)); await(() -> !of("audio").isEmpty()); assertTrue(payments.isEmpty());
    }
    @Test void commandRequestsAlsoWaitForClientConfirmation() throws Exception {
        start(true); server.command(player, new String[]{"request", "101"});
        await(() -> !of("requestQuote").isEmpty()); assertTrue(payments.isEmpty());
        send("confirmRequest", confirmation(last("requestQuote").get("quoteId").getAsString()));
        await(() -> !of("audio").isEmpty()); assertEquals(1, payments.size());
    }
    @Test void paidRequestSurvivesLeavingAndKeepsItsOriginalBackend() throws Exception {
        delayed = new CompletableFuture<>(); start(true);
        send("confirmRequest", confirmation(quote(101))); await(() -> !payments.isEmpty());
        server.disconnect(player.id()); route = "fixture-backend-b"; delayed.complete(EconomyService.Result.paid());
        send("hello", new JsonObject()); send("subscribe", new JsonObject());
        await(() -> !of("audio").isEmpty());
        assertEquals("fixture-backend-a", payments.getFirst().backend()); assertEquals(1, withdrawals.size());
    }
    @Test void restartRechecksTheSamePaymentAndRestoresTheAcceptedSong() throws Exception {
        receipt = EconomyService.Result.pending(); start(true);
        String rid = send("confirmRequest", confirmation(quote(101))); awaitResult(rid);
        String transaction = payments.getFirst().id();
        server.close(); server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS); messages.clear();
        receipt = EconomyService.Result.paid(); route = "fixture-backend-b"; start(true);
        await(() -> !of("audio").isEmpty());
        assertEquals(1, withdrawals.size()); assertEquals(2, payments.size());
        assertEquals(transaction, payments.getLast().id()); assertEquals("fixture-backend-a", payments.getLast().backend());
        assertEquals(transaction, last("audio").getAsJsonObject("current").get("entryId").getAsString());
    }
    @Test void reloadKeepsAnAcceptedPaymentAtItsQuotedPriceAndUpdatesNewQuotes() throws Exception {
        delayed = new CompletableFuture<>(); start(true);
        String first = send("confirmRequest", confirmation(quote(101)));
        await(() -> payments.size() == 1);
        Files.writeString(directory.resolve("config.properties"), "server-id=synthetic-network\nrequest-fee-enabled=true\nrequest-fee-amount=20\ncurrency-name=新版测试币\nrequest-cooldown-seconds=0\n");
        server.command(MusicServer.console(), new String[]{"reload"});
        await(() -> last("welcome").get("requestPrice").getAsString().equals("20"));
        delayed.complete(EconomyService.Result.paid());
        awaitResult(first); await(() -> !of("audio").isEmpty());
        assertEquals(1, payments.size()); assertEquals(new BigDecimal("12.50"), payments.getFirst().amount());
        String next = quote(102);
        assertEquals("20", last("requestQuote").get("amount").getAsString());
        assertEquals("新版测试币", last("requestQuote").get("currencyName").getAsString());
        String second = send("confirmRequest", confirmation(next)); awaitResult(second);
        assertEquals(2, payments.size()); assertEquals(2, withdrawals.size());
        assertEquals(0, new BigDecimal("20").compareTo(payments.getLast().amount()));
    }
}
