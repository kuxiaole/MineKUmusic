package me.sorihiko.dango.server.folia;

import io.papermc.paper.threadedregions.scheduler.*;
import me.sorihiko.dango.server.EconomyService;
import net.milkbowl.vault.economy.*;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import static me.sorihiko.dango.server.EconomyService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated synthetic economy and entity scheduler; no real player, balance or server is used. */
class VaultPaymentsTest {
    @TempDir Path directory;
    private final UUID playerId = UUID.randomUUID();
    private final AtomicInteger withdrawals = new AtomicInteger();
    private final ExecutorService region = Executors.newSingleThreadExecutor(r -> new Thread(r, "synthetic-player-region"));
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private volatile RegisteredServiceProvider<Economy> registration;
    private volatile boolean enough = true, failBeforeWithdrawal, failDuringWithdrawal;
    private Plugin plugin;
    private Economy provider;
    private VaultPayments payments;

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "Synthetic " + type.getSimpleName();
            };
            return calls.invoke(proxy, method, args);
        });
    }
    private static AssertionError unexpected(Method method) { return new AssertionError("Unexpected fixture call: " + method); }
    private void ownerThread() { assertEquals("synthetic-player-region", Thread.currentThread().getName()); }
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        ServicesManager services = stub(ServicesManager.class, (p, m, a) -> {
            assertEquals("getRegistration", m.getName()); assertEquals(Economy.class, a[0]); return registration;
        });
        Server server = stub(Server.class, (p, m, a) -> {
            if (m.getName().equals("getServicesManager")) return services;
            throw unexpected(m); // No plugin-name whitelist may be consulted.
        });
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        plugin = stub(Plugin.class, (p, m, a) -> switch (m.getName()) {
            case "isEnabled" -> true; case "getServer" -> server; case "getLogger" -> logger;
            default -> throw unexpected(m);
        });
        ScheduledTask task = stub(ScheduledTask.class, (p, m, a) -> { throw unexpected(m); });
        EntityScheduler scheduler = stub(EntityScheduler.class, (p, m, a) -> {
            assertEquals("run", m.getName()); assertSame(plugin, a[0]);
            region.execute(() -> ((Consumer<ScheduledTask>) a[1]).accept(task));
            return task;
        });
        Player player = stub(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getScheduler" -> scheduler;
            case "isOnline" -> { ownerThread(); yield true; }
            default -> throw unexpected(m);
        });
        players.put(playerId, player);
        provider = stub(Economy.class, (p, m, a) -> {
            if (m.getName().equals("isEnabled")) return true;
            if (m.getName().equals("getName")) return "Synthetic XConomy";
            ownerThread();
            return switch (m.getName()) {
                case "fractionalDigits" -> 2;
                case "has" -> {
                    assertSame(player, a[0]);
                    if (failBeforeWithdrawal) throw new IllegalStateException("Synthetic provider not ready");
                    yield enough;
                }
                case "withdrawPlayer" -> {
                    assertSame(player, a[0]); assertEquals(10.25, a[1]); withdrawals.incrementAndGet();
                    if (failDuringWithdrawal) throw new IllegalStateException("Synthetic indeterminate receipt");
                    yield new EconomyResponse(10.25, 89.75, EconomyResponse.ResponseType.SUCCESS, "");
                }
                default -> throw unexpected(m);
            };
        });
        registerProvider();
        payments = new VaultPayments(plugin, players, directory);
    }
    private void registerProvider() {
        Plugin owner = stub(Plugin.class, (p, m, a) -> {
            if (m.getName().equals("getName")) return "ServiceIO";
            throw unexpected(m);
        });
        registration = new RegisteredServiceProvider<>(Economy.class, provider, ServicePriority.Normal, owner);
    }
    private Payment payment() { return new Payment(UUID.randomUUID().toString(), playerId, "Synthetic payer", new BigDecimal("10.25"), "fixture-backend", System.currentTimeMillis()); }
    private Result result(CompletionStage<Result> stage) throws Exception { return stage.toCompletableFuture().get(5, TimeUnit.SECONDS); }
    @AfterEach void stop() throws Exception { payments.close(); region.shutdown(); assertTrue(region.awaitTermination(5, TimeUnit.SECONDS)); }

    @Test void serviceIoRegistrationChargesOnPlayerSchedulerAndRetryDoesNotChargeAgain() throws Exception {
        assertEquals("Synthetic XConomy (ServiceIO)", payments.providerDescription());
        Payment payment = payment();
        assertEquals(Status.PAID, result(payments.charge(payment)).status());
        assertEquals(Status.PAID, result(payments.charge(payment)).status());
        assertEquals(1, withdrawals.get());
    }
    @Test void providerRegisteredAfterStartupIsDetectedWithoutReload() throws Exception {
        registration = null; assertNull(payments.providerDescription());
        registerProvider();
        assertEquals(Status.PAID, result(payments.charge(payment())).status());
        assertEquals(1, withdrawals.get());
    }
    @Test void missingProviderRejectsFreshChargeButPreservesPaidReceiptAfterRestart() throws Exception {
        Payment paid = payment(); assertEquals(Status.PAID, result(payments.charge(paid)).status());
        payments.close(); registration = null; payments = new VaultPayments(plugin, players, directory);
        assertEquals(Status.PAID, result(payments.charge(paid)).status());
        Result rejected = result(payments.charge(payment()));
        assertEquals(Status.REJECTED, rejected.status()); assertEquals("The server economy is unavailable", rejected.message());
        assertEquals(1, withdrawals.get());
    }
    @Test void insufficientBalanceNeverWithdraws() throws Exception {
        enough = false;
        assertEquals(Status.REJECTED, result(payments.charge(payment())).status());
        assertEquals(0, withdrawals.get());
    }
    @Test void failureBeforeWithdrawalRejectsInsteadOfCreatingAnUncertainPayment() throws Exception {
        failBeforeWithdrawal = true;
        assertEquals(Status.REJECTED, result(payments.charge(payment())).status());
        assertEquals(0, withdrawals.get());
    }
    @Test void uncertainWithdrawalStaysUncertainAfterProviderDisappearsAndRestart() throws Exception {
        failDuringWithdrawal = true; Payment payment = payment();
        assertEquals(Status.UNKNOWN, result(payments.charge(payment)).status());
        payments.close(); registration = null; payments = new VaultPayments(plugin, players, directory);
        assertEquals(Status.UNKNOWN, result(payments.charge(payment)).status());
        assertEquals(1, withdrawals.get());
    }
    @Test void absentOptionalVaultApiStillLoadsJournalAndRejectsNewPayments() throws Exception {
        Payment paid = payment(); assertEquals(Status.PAID, result(payments.charge(paid)).status()); payments.close();
        String adapter = VaultPayments.class.getName();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{VaultPayments.class.getProtectionDomain().getCodeSource().getLocation()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("net.milkbowl.vault.")) throw new ClassNotFoundException("Synthetic missing optional API");
                    if (!name.equals(adapter) && !name.startsWith(adapter + "$")) return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name); if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded); return loaded;
                }
            }
        }) {
            Class<?> type = loader.loadClass(adapter);
            var constructor = type.getDeclaredConstructor(Plugin.class, Map.class, Path.class); constructor.setAccessible(true);
            try (EconomyService withoutApi = (EconomyService) constructor.newInstance(plugin, players, directory)) {
                var description = type.getDeclaredMethod("providerDescription"); description.setAccessible(true);
                assertNull(description.invoke(withoutApi));
                assertEquals(Status.PAID, result(withoutApi.charge(paid)).status());
                assertEquals(Status.REJECTED, result(withoutApi.charge(payment())).status());
            }
        }
        assertEquals(1, withdrawals.get());
    }
}
