package me.sorihiko.dango.server.velocity;

import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import me.sorihiko.dango.server.*;
import java.util.*;
import java.util.concurrent.*;

final class VelocityEconomy implements EconomyService {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(EconomyWire.CHANNEL);
    private record Pending(Payment payment, CompletableFuture<Result> result) {}
    private final ProxyServer proxy;
    private final Object plugin;
    private final EconomyWire wire;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    VelocityEconomy(ProxyServer proxy, Object plugin, String secret) {
        this.proxy = proxy; this.plugin = plugin; wire = new EconomyWire(secret);
    }
    @Override public String route(UUID player) {
        return proxy.getPlayer(player).flatMap(Player::getCurrentServer).map(c -> c.getServerInfo().getName())
                .orElseThrow(() -> new IllegalStateException("Wait until you have connected to a backend server before requesting a song"));
    }
    @Override public synchronized CompletionStage<Result> charge(Payment payment) {
        if (closed) return CompletableFuture.completedFuture(Result.pending());
        Pending request = new Pending(payment, new CompletableFuture<>());
        Pending old = pending.putIfAbsent(payment.id(), request);
        if (old != null) return old.result;
        ScheduledTask timeout = proxy.getScheduler().buildTask(plugin, () -> request.result.complete(Result.pending(
                "The payment backend did not return a receipt; check its economy bridge configuration"))).delay(8, TimeUnit.SECONDS).schedule();
        tasks.add(timeout);
        request.result.whenComplete((result, error) -> { pending.remove(payment.id(), request); timeout.cancel(); tasks.remove(timeout); });
        byte[] payload = wire.encode(new EconomyWire.Message("charge", payment, null));
        boolean sent = proxy.getServer(payment.backend()).map(server -> server.sendPluginMessage(CHANNEL, payload)).orElse(false);
        if (!sent) request.result.complete(Result.pending("The payment backend has no plugin-message connection; waiting to check the transaction"));
        return request.result;
    }
    String receive(ServerConnection connection, byte[] bytes) {
        try {
            EconomyWire.Message message = wire.decode(bytes);
            if (!message.type().equals("receipt") || message.result() == null || message.result().status() == null) return null;
            Pending request = pending.get(message.payment().id());
            if (request != null && request.payment.equals(message.payment()) && request.payment.backend().equals(connection.getServerInfo().getName()))
                request.result.complete(message.result());
            return null;
        } catch (IllegalArgumentException error) { return error.getMessage(); }
    }
    @Override public synchronized void close() {
        closed = true; pending.values().forEach(p -> p.result.complete(Result.pending())); pending.clear();
        tasks.forEach(ScheduledTask::cancel); tasks.clear();
    }
}
