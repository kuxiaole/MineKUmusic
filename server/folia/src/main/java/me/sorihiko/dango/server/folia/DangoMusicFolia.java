package me.sorihiko.dango.server.folia;

import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.server.*;
import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class DangoMusicFolia extends JavaPlugin implements Listener, MusicServer.Endpoint {
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private volatile MusicServer music;
    private EconomyService economy = EconomyService.UNAVAILABLE;
    private Path directory;
    private volatile ServerConfig config;
    private volatile MusicMessages language = MusicMessages.defaults();
    private MusicMessages messages() { MusicServer current = music; return current == null ? language : current.messages(); }
    private void tellKey(UUID id, String key, Object... args) { notice(id, messages().render(key, args)); }
    private final AtomicBoolean reloading = new AtomicBoolean();
    private final AtomicBoolean bridgeWarningShown = new AtomicBoolean();
    @Override public void onEnable() {
        try {
            directory = ServerConfig.dataDirectory(getDataFolder().toPath(), "minekumusic", "DangoMusic");
            getLogger().info("Music data directory: " + directory);
            config = ServerConfig.load(directory);
            language = MusicMessages.load(directory);
            VaultPayments payments = new VaultPayments(this, players, directory);
            economy = payments;
            String provider = payments.providerDescription();
            if (provider != null) getLogger().info(language.text("economy.connected", "provider", provider));
            else if (config.requestPrice().signum() > 0 || config.economyBridgeOnly())
                getLogger().warning(language.text("economy.unavailable"));
            getServer().getPluginManager().registerEvents(this, this);
            Objects.requireNonNull(getCommand("minekumusic")).setExecutor(this);
            Objects.requireNonNull(getCommand("minekumusic")).setTabCompleter(this);
            if (config.economyBridgeOnly()) {
                EconomyWire wire = new EconomyWire(config.economyBridgeSecret());
                getServer().getMessenger().registerOutgoingPluginChannel(this, EconomyWire.CHANNEL);
                getServer().getMessenger().registerIncomingPluginChannel(this, EconomyWire.CHANNEL, (channel, carrier, bytes) -> {
                    try {
                        EconomyWire.Message message = wire.decode(bytes);
                        if (!message.type().equals("charge")) return;
                        atPlayer(carrier, () -> {
                            players.put(carrier.getUniqueId(), carrier);
                            economy.charge(message.payment()).thenAccept(result -> {
                                byte[] response = wire.encode(new EconomyWire.Message("receipt", message.payment(), result));
                                atPlayer(carrier, () -> { if (carrier.isOnline()) carrier.sendPluginMessage(this, EconomyWire.CHANNEL, response); });
                            });
                        });
                    } catch (IllegalArgumentException error) {
                        if (bridgeWarningShown.compareAndSet(false, true))
                            getLogger().warning(messages().text("economy.bridge-invalid", "reason", error.getMessage()));
                    }
                });
                getLogger().info("Economy bridge enabled; the music queue is managed by Velocity.");
                return;
            }
            music = new MusicServer(directory, config, new ChannelMusicProvider(directory), this, economy, language);
            getServer().getMessenger().registerOutgoingPluginChannel(this, MusicProtocol.CHANNEL);
            getServer().getMessenger().registerIncomingPluginChannel(this, MusicProtocol.CHANNEL, (channel, player, bytes) -> {
                byte[] copy = bytes.clone();
                atPlayer(player, () -> {
                    MusicServer current = music;
                    if (current != null) { players.put(player.getUniqueId(), player); current.receive(peer(player), copy); }
                });
            });
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not start MineKUmusic", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    private void atPlayer(Player player, Runnable task) {
        if (!isEnabled()) return;
        player.getScheduler().run(this, ignored -> { if (isEnabled()) task.run(); }, null);
    }
    private MusicServer.Peer peer(Player player) {
        return new MusicServer.Peer(player.getUniqueId(), player.getName(), player.hasPermission("dangomusic.use"),
                player.hasPermission("dangomusic.request"), player.hasPermission("dangomusic.vote"), player.hasPermission("dangomusic.admin"));
    }
    @EventHandler public void joined(PlayerJoinEvent event) { players.put(event.getPlayer().getUniqueId(), event.getPlayer()); }
    @EventHandler public void left(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); players.remove(id);
        MusicServer current = music; if (current != null) current.disconnect(id);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) atPlayer(player, () -> dispatch(peer(player), args));
        else if (sender instanceof ConsoleCommandSender) dispatch(MusicServer.console(), args);
        else sender.sendMessage(messages().render("command.sender"));
        return true;
    }
    private MusicCommands.Mode mode() { return config != null && config.economyBridgeOnly() ? MusicCommands.Mode.BRIDGE : MusicCommands.Mode.FOLIA; }
    private void dispatch(MusicServer.Peer peer, String[] args) {
        String name = MusicCommands.name(args);
        if (!MusicCommands.known(name, mode())) { tellKey(peer.id(), "command.unknown"); return; }
        if (!MusicCommands.allowed(peer, name, mode())) { tellKey(peer.id(), "command.denied"); return; }
        if (name.equals("payment")) { reconcilePayment(peer, args); return; }
        if (mode() == MusicCommands.Mode.BRIDGE) {
            if (name.equals("help")) {
                if (args.length > 2) { tellKey(peer.id(), "command.usage", "usage", MusicCommands.usage(messages(), name)); return; }
                chat(peer.id(), MusicCommands.help(messages(), peer, mode(), args.length == 2 ? args[1] : ""));
            } else if (name.equals("reload")) {
                if (args.length != 1) { tellKey(peer.id(), "command.usage", "usage", MusicCommands.usage(messages(), name)); return; }
                reloadBridge(peer);
            }
            return;
        }
        MusicServer current = music;
        if (current == null) tellKey(peer.id(), "command.not-ready");
        else current.command(peer, args, MusicCommands.Mode.FOLIA);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Folia 26.2 / 26.3 dispatch player completions on the player's entity scheduler.
        MusicServer.Peer peer = sender instanceof Player player ? peer(player)
                : sender instanceof ConsoleCommandSender ? MusicServer.console() : null;
        if (peer == null) return List.of();
        MusicServer current = music;
        return current == null ? MusicCommands.suggest(peer, mode(), args, MusicCommands.Snapshot.EMPTY) : current.suggest(peer, args, mode());
    }
    private void reloadBridge(MusicServer.Peer peer) {
        if (!reloading.compareAndSet(false, true)) { tellKey(peer.id(), "reload.busy"); return; }
        tellKey(peer.id(), "reload.loading");
        getServer().getAsyncScheduler().runNow(this, task -> {
            try {
                MusicMessages nextLanguage = MusicMessages.load(directory);
                ServerConfig next = ServerConfig.load(directory, config);
                if (isEnabled()) {
                    config = next;
                    language = nextLanguage;
                    tellKey(peer.id(), "reload.bridge");
                }
            } catch (Exception e) { tellKey(peer.id(), "reload.failed", "reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
            finally { reloading.set(false); }
        });
    }
    private void reconcilePayment(MusicServer.Peer peer, String[] args) {
        if (!peer.admin()) { tellKey(peer.id(), "command.denied"); return; }
        if (args.length != 3 || !Set.of("paid", "cancel").contains(args[2].toLowerCase(Locale.ROOT))) { tellKey(peer.id(), "payment.usage", "usage", MusicCommands.usage(messages(), "payment")); return; }
        if (!(economy instanceof VaultPayments vault)) { tellKey(peer.id(), "payment.unavailable"); return; }
        vault.reconcile(args[1], args[2].equalsIgnoreCase("paid")).thenAccept(result -> notice(peer.id(), messages().translated(result.message(), result.status() == EconomyService.Status.PAID)));
    }
    @Override public void send(UUID id, List<byte[]> frames) {
        Player player = players.get(id);
        if (player != null) atPlayer(player, () -> {
            if (!player.isOnline()) return;
            for (byte[] frame : frames) player.sendPluginMessage(this, MusicProtocol.CHANNEL, frame);
        });
    }
    @Override public void tell(UUID id, String text) {
        notice(id, messages().translated(text, true));
    }
    @Override public void notice(UUID id, Component content) {
        chat(id, List.of(MusicChat.notice(messages(), content)));
    }
    @Override public void chat(UUID id, List<Component> lines) {
        List<Component> copy = List.copyOf(lines);
        if (id.equals(MusicServer.console().id())) { copy.forEach(line -> getLogger().info(MusicChat.plain(line))); return; }
        Player player = players.get(id);
        if (player != null) atPlayer(player, () -> { if (player.isOnline()) copy.forEach(player::sendMessage); });
    }
    @Override public void announce(Component content) {
        if (!isEnabled() || music == null) return;
        List<Component> lines = List.of(content);
        players.keySet().forEach(id -> chat(id, lines));
    }
    @Override public void votePrompt(UUID id, String text, String command) {
        votePrompt(id, messages().render("notice.text", "text", text), command);
    }
    @Override public void votePrompt(UUID id, Component text, String command) {
        chat(id, List.of(MusicChat.vote(messages(), text, command)));
    }
    @Override public void log(String message, Throwable error) { getLogger().log(Level.WARNING, message, error); }
    @Override public void onDisable() {
        MusicServer current = music; music = null;
        if (current != null) current.close();
        economy.close();
        getServer().getAsyncScheduler().cancelTasks(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        HandlerList.unregisterAll((Listener) this); players.clear();
    }
}
