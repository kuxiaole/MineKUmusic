package me.sorihiko.dango.server.folia;

import me.sorihiko.dango.server.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

final class VaultPayments implements EconomyService {
    private final JavaPlugin plugin;
    private final Map<UUID, Player> players;
    private final PaymentJournal journal;
    VaultPayments(JavaPlugin plugin, Map<UUID, Player> players, Path directory) throws Exception {
        this.plugin = plugin; this.players = players; journal = new PaymentJournal(directory);
    }
    @Override public String route(UUID player) {
        if (!players.containsKey(player)) throw new IllegalStateException("The player is not connected to this economy server");
        return "local";
    }
    @Override public CompletionStage<Result> charge(Payment payment) { return journal.charge(payment, this::withdraw); }
    CompletionStage<Result> reconcile(String id, boolean paid) { return journal.reconcile(id, paid); }
    private CompletionStage<Result> withdraw(Payment payment) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        Player player = players.get(payment.playerId());
        if (player == null || !plugin.isEnabled()) { answer.complete(Result.rejected("The player left before payment; no payment was made")); return answer; }
        Runnable retired = () -> answer.complete(Result.rejected("The player left before payment; no payment was made"));
        try {
            var task = player.getScheduler().run(plugin, ignored -> {
                if (!plugin.isEnabled() || !player.isOnline()) { retired.run(); return; }
                try {
                    var registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
                    if (registration == null) { answer.complete(Result.rejected("The server economy is unavailable")); return; }
                    Economy economy = registration.getProvider();
                    int decimals = economy.fractionalDigits();
                    if (decimals >= 0 && payment.amount().stripTrailingZeros().scale() > decimals) {
                        answer.complete(Result.rejected("The configured price has more decimal places than the economy supports")); return;
                    }
                    double amount = payment.amount().doubleValue();
                    if (!economy.has(player, amount)) { answer.complete(Result.rejected("You do not have enough currency to request this song")); return; }
                    EconomyResponse response = economy.withdrawPlayer(player, amount);
                    answer.complete(response.transactionSuccess() ? Result.paid() : Result.rejected("The economy rejected the payment; the song was not requested"));
                } catch (Exception e) {
                    plugin.getLogger().warning("Check interrupted payment " + payment.id() + " for player " + payment.playerId());
                    answer.complete(Result.unknown());
                }
            }, retired);
            if (task == null) retired.run();
        } catch (IllegalStateException e) { retired.run(); }
        return answer;
    }
    @Override public void close() { journal.close(); }
}
