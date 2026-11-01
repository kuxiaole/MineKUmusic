package me.sorihiko.dango.server;

import me.sorihiko.dango.music.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static me.sorihiko.dango.server.AccountMembership.Status.*;

class ServerAccountsTest {
    @TempDir Path directory;
    final ExecutorService owner = Executors.newSingleThreadExecutor(), io = Executors.newFixedThreadPool(2), disk = Executors.newSingleThreadExecutor();
    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    final AtomicReference<Function<NativeAccount, AccountMembership.Result>> verifier = new AtomicReference<>(account -> valid());
    ServerAccounts accounts;
    AccountMembership.Result valid() { return new AccountMembership.Result(VALID, "123", now.get() + 86_400_000); }
    @BeforeEach void start() { accounts = create(); }
    ServerAccounts create() { return new ServerAccounts(directory, io, disk, owner::execute, () -> {}, a -> verifier.get().apply(a), now::get); }
    @AfterEach void stop() throws Exception {
        accounts.close(); io.shutdownNow(); disk.shutdown(); owner.shutdown();
        assertTrue(io.awaitTermination(5, TimeUnit.SECONDS)); assertTrue(disk.awaitTermination(5, TimeUnit.SECONDS)); assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
    }
    <T> T call(Callable<T> task) throws Exception { return owner.submit(task).get(5, TimeUnit.SECONDS); }
    void waitFor(Callable<CompletionStage<Void>> task) throws Exception { call(task).toCompletableFuture().get(5, TimeUnit.SECONDS); }
    void check(String source) throws Exception { waitFor(() -> accounts.check(source)); }
    static NativeAccount account(String source, String key) {
        var cookies = new CookieManager();
        for (var pair : source.equals("qq") ? Map.of("qqmusic_uin", "123", "qqmusic_key", key).entrySet() : Map.of("MUSIC_U", key).entrySet()) {
            HttpCookie cookie = new HttpCookie(pair.getKey(), pair.getValue());
            cookie.setDomain(source.equals("qq") ? ".qq.com" : ".music.163.com"); cookie.setPath("/");
            cookies.getCookieStore().add(URI.create(source.equals("qq") ? "https://y.qq.com/" : "https://music.163.com/"), cookie);
        }
        return new NativeAccount(source.equals("qq") ? MusicSource.QQ : MusicSource.NETEASE, "123", "Fixture", "", "", cookies);
    }
    void share(UUID player, String source) throws Exception {
        waitFor(() -> accounts.accept(new ServerLogins.Request(player, player.equals(alice) ? "Alice" : "Bob", source, true, accounts.generation(source)), account(source, "synthetic-" + player), () -> true));
    }
    String discount(UUID player, String source) throws Exception { return call(() -> accounts.discount(player, source)); }
    String state(String source) throws Exception { return call(() -> accounts.snapshot(alice, false).asList().stream().map(e -> e.getAsJsonObject()).filter(e -> e.get("source").getAsString().equals(source)).findFirst().orElseThrow().get("state").getAsString()); }
    @Test void contributionOnlyBenefitsItsPlayerAndPlatformAndWithdrawalDoesNotResurrectLegacyCookies() throws Exception {
        check("netease"); share(alice, "netease");
        assertFalse(discount(alice, "netease").isEmpty()); assertEquals("", discount(alice, "qq")); assertEquals("", discount(bob, "netease"));
        Files.copy(directory.resolve("netease-cookie.json"), directory.resolve("cookie.json"));
        waitFor(() -> accounts.remove(alice, false, "netease", accounts.generation("netease")));
        check("netease"); assertEquals("", discount(alice, "netease")); assertEquals("missing", state("netease"));
        assertEquals("[]", Files.readString(directory.resolve("netease-cookie.json")));
        share(bob, "netease"); assertFalse(discount(bob, "netease").isEmpty());
    }
    @Test void nonMemberCannotReplaceTheCurrentAccountAndDoesNotEnterCommit() throws Exception {
        check("qq"); share(alice, "qq"); String cookie = Files.readString(directory.resolve("qq-cookie.json"));
        verifier.set(a -> AccountMembership.Result.temporary()); check("qq");
        verifier.set(a -> new AccountMembership.Result(NON_MEMBER, "123", 0));
        AtomicBoolean committed = new AtomicBoolean();
        var failure = assertThrows(ExecutionException.class, () -> waitFor(() -> accounts.accept(new ServerLogins.Request(bob, "Bob", "qq", true, accounts.generation("qq")), account("qq", "synthetic-new"), () -> { committed.set(true); return true; })));
        assertInstanceOf(ServerAccounts.Failure.class, failure.getCause()); assertFalse(committed.get());
        assertEquals(cookie, Files.readString(directory.resolve("qq-cookie.json"))); assertFalse(discount(alice, "qq").isEmpty());
    }
    @Test void explicitExpiryRevokesImmediatelyAndRecoveryRestoresTheSameContributor() throws Exception {
        check("qq"); share(alice, "qq");
        verifier.set(a -> AccountMembership.Result.invalid()); check("qq"); assertEquals("", discount(alice, "qq"));
        assertTrue(call(() -> accounts.canContribute("qq")));
        verifier.set(a -> AccountMembership.Result.temporary()); check("qq"); assertEquals("", discount(alice, "qq"));
        verifier.set(a -> valid()); check("qq"); assertFalse(discount(alice, "qq").isEmpty());
    }
    @Test void transientGraceIsBoundedAcrossRepeatedFailuresAndRestart() throws Exception {
        check("qq"); share(alice, "qq"); long verified = now.get();
        verifier.set(a -> AccountMembership.Result.temporary()); now.addAndGet(60_000); check("qq");
        assertFalse(discount(alice, "qq").isEmpty()); assertTrue(call(() -> accounts.canContribute("qq")));
        accounts.close(); accounts = create(); now.set(verified + 250_000); check("qq");
        assertFalse(discount(alice, "qq").isEmpty());
        now.set(verified + ServerAccounts.GRACE_MS); check("qq"); assertEquals("", discount(alice, "qq"));
        accounts.close(); accounts = create(); check("qq"); assertEquals("", discount(alice, "qq"));
    }
    @Test void graceNeverExtendsKnownMembershipExpiry() throws Exception {
        long until = now.get() + 50_000; verifier.set(a -> new AccountMembership.Result(VALID, "123", until));
        check("qq"); share(alice, "qq"); verifier.set(a -> AccountMembership.Result.temporary()); check("qq");
        now.set(until); assertEquals("", discount(alice, "qq"));
    }
    @Test void replacementMovesCreditAndFencesOldLoginAndWithdrawalRequests() throws Exception {
        check("qq"); share(alice, "qq"); String old = call(() -> accounts.generation("qq"));
        verifier.set(a -> AccountMembership.Result.temporary()); check("qq"); verifier.set(a -> valid()); share(bob, "qq");
        assertEquals("", discount(alice, "qq")); assertFalse(discount(bob, "qq").isEmpty());
        assertThrows(ExecutionException.class, () -> waitFor(() -> accounts.accept(new ServerLogins.Request(alice, "Alice", "qq", true, old), account("qq", "old"), () -> true)));
        assertThrows(ExecutionException.class, () -> call(() -> accounts.remove(alice, false, "qq", old)));
        assertFalse(discount(bob, "qq").isEmpty());
    }
    @Test void externalCookieReplacementDuringACoalescedCheckCannotInheritCredit() throws Exception {
        check("qq"); share(alice, "qq");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger checks = new AtomicInteger();
        verifier.set(a -> { checks.incrementAndGet(); entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); } return valid(); });
        CompletionStage<Void> first = call(() -> accounts.check("qq")); assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertSame(first, call(() -> accounts.check("qq")));
        ServerCookies.save(directory, "qq", account("qq", "manually-replaced").getCookies()); release.countDown(); first.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, checks.get()); assertEquals("", discount(alice, "qq"));
        verifier.set(a -> valid()); check("qq"); assertEquals("valid", state("qq")); assertEquals("", discount(alice, "qq"));
    }
    @Test void cancelledOrFailedCommitCannotCreateAContribution() throws Exception {
        check("qq");
        assertThrows(CancellationException.class, () -> call(() -> accounts.accept(new ServerLogins.Request(alice, "Alice", "qq", true, ""), account("qq", "fixture"), () -> false)).toCompletableFuture().join());
        assertFalse(Files.exists(directory.resolve("qq-cookie.json")));
        Files.createDirectory(directory.resolve("qq-contribution.json"));
        assertThrows(ExecutionException.class, () -> share(alice, "qq")); assertEquals("", discount(alice, "qq"));
        assertFalse(Files.exists(directory.resolve("qq-cookie.json")));
    }
    @Test void lateHealthResultCannotRestoreTheReplacedContributor() throws Exception {
        check("qq"); share(alice, "qq"); verifier.set(a -> AccountMembership.Result.temporary()); check("qq");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        verifier.set(a -> {
            if (CookieValues.cookie(a.getCookies(), "qqmusic_key").endsWith(alice.toString())) {
                entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return valid();
        });
        CompletionStage<Void> pending = call(() -> accounts.check("qq")); assertTrue(entered.await(5, TimeUnit.SECONDS));
        try { share(bob, "qq"); } finally { release.countDown(); }
        pending.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("", discount(alice, "qq")); assertFalse(discount(bob, "qq").isEmpty());
        assertThrows(ExecutionException.class, () -> call(() -> accounts.remove(alice, false, "qq", accounts.generation("qq"))));
    }
    @Test void unverifiedNewAccountNeverGetsTheTransientGracePeriod() throws Exception {
        check("qq"); verifier.set(a -> AccountMembership.Result.temporary());
        assertThrows(ExecutionException.class, () -> share(alice, "qq"));
        assertEquals("", discount(alice, "qq")); assertFalse(Files.exists(directory.resolve("qq-cookie.json")));
    }
}
