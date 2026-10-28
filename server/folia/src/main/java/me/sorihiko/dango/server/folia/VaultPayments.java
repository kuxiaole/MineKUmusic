package me.sorihiko.dango.server.folia;

import me.sorihiko.dango.server.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

final class VaultPayments implements EconomyService {
    private final Plugin plugin;
    private final Map<UUID, Player> players;
    private final PaymentJournal journal;
    VaultPayments(Plugin plugin, Map<UUID, Player> players, Path directory) throws Exception {
        this.plugin = plugin; this.players = players; journal = new PaymentJournal(directory);
    }
    @Override public String route(UUID player) {
        if (!players.containsKey(player)) throw new IllegalStateException("The player is not connected to this economy server");
        return "local";
    }
    @Override public CompletionStage<Result> charge(Payment payment) { return journal.charge(payment, this::withdraw); }
    CompletionStage<Result> reconcile(String id, boolean paid) { return journal.reconcile(id, paid); }
    String providerDescription() {
        try { return VaultAccess.describe(plugin); }
        catch (LinkageError | RuntimeException unavailable) { return null; }
    }
    private CompletionStage<Result> withdraw(Payment payment) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        Player player = players.get(payment.playerId());
        if (player == null || !plugin.isEnabled()) { answer.complete(Result.rejected("The player left before payment; no payment was made")); return answer; }
        Runnable retired = () -> answer.complete(Result.rejected("The player left before payment; no payment was made"));
        try {
            var task = player.getScheduler().run(plugin, ignored -> {
                if (!plugin.isEnabled() || !player.isOnline()) { retired.run(); return; }
                try {
                    answer.complete(VaultAccess.withdraw(plugin, player, payment));
                } catch (LinkageError unavailable) {
                    // The optional Vault API itself is absent; no withdrawal was invoked.
                    answer.complete(Result.rejected("The server economy is unavailable"));
                }
            }, retired);
            if (task == null) retired.run();
        } catch (IllegalStateException e) { retired.run(); }
        return answer;
    }
    @Override public void close() { journal.close(); }

    // Keep optional API linkage separate so the journal remains usable without Vault / ServiceIO.
    private static final class VaultAccess {
        static String describe(Plugin plugin) {
            var registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (registration == null || !registration.getProvider().isEnabled()) return null;
            return registration.getProvider().getName() + " (" + registration.getPlugin().getName() + ")";
        }
        static Result withdraw(Plugin plugin, Player player, Payment payment) {
            boolean withdrawalStarted = false;
            try {
                var registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
                if (registration == null || !registration.getProvider().isEnabled())
                    return Result.rejected("The server economy is unavailable");
                Economy economy = registration.getProvider();
                int decimals = economy.fractionalDigits();
                if (decimals >= 0 && payment.amount().stripTrailingZeros().scale() > decimals)
                    return Result.rejected("The configured price has more decimal places than the economy supports");
                double amount = payment.amount().doubleValue();
                if (!economy.has(player, amount))
                    return Result.rejected("You do not have enough currency to request this song");
                withdrawalStarted = true;
                EconomyResponse response = economy.withdrawPlayer(player, amount);
                return response.transactionSuccess() ? Result.paid() : Result.rejected("The economy rejected the payment; the song was not requested");
            } catch (RuntimeException | LinkageError error) {
                plugin.getLogger().log(Level.WARNING, "Economy failure " + (withdrawalStarted ? "during" : "before")
                        + " withdrawal: transaction=" + payment.id() + " player=" + payment.playerId(), error);
                return withdrawalStarted ? Result.unknown() : Result.rejected("The server economy is unavailable");
            }
        }
    }
}
