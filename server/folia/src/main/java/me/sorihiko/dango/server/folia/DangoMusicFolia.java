package me.sorihiko.dango.server.folia;

import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.server.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private final AtomicBoolean reloading = new AtomicBoolean();
    @Override public void onEnable() {
        try {
            directory = ServerConfig.dataDirectory(getDataFolder().toPath(), "minekumusic", "DangoMusic");
            getLogger().info("Music data directory: " + directory);
            config = ServerConfig.load(directory);
            if (getServer().getPluginManager().isPluginEnabled("Vault") || getServer().getPluginManager().isPluginEnabled("VaultUnlocked"))
                economy = new VaultPayments(this, players, directory);
            else if (config.requestPrice().signum() > 0 || config.economyBridgeOnly())
                getLogger().warning("Vault is unavailable; paid song requests cannot be processed.");
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
                    } catch (IllegalArgumentException ignored) {}
                });
                getLogger().info("Economy bridge enabled; the music queue is managed by Velocity.");
                return;
            }
            music = new MusicServer(directory, config, new NeteaseProvider(directory), this, economy);
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
        else sender.sendMessage("此指令仅支持玩家或服务器控制台。");
        return true;
    }
    private MusicCommands.Mode mode() { return config != null && config.economyBridgeOnly() ? MusicCommands.Mode.BRIDGE : MusicCommands.Mode.FOLIA; }
    private void dispatch(MusicServer.Peer peer, String[] args) {
        String name = MusicCommands.name(args);
        if (!MusicCommands.known(name, mode())) { tell(peer.id(), "未知或不适用于当前模式的指令，请使用 /minekumusic help。"); return; }
        if (!MusicCommands.allowed(peer, name, mode())) { tell(peer.id(), "你没有权限使用该指令，或该指令仅限游戏内玩家使用。"); return; }
        if (name.equals("payment")) { reconcilePayment(peer, args); return; }
        if (mode() == MusicCommands.Mode.BRIDGE) {
            if (name.equals("help")) {
                if (args.length > 2) { tell(peer.id(), "用法：" + MusicCommands.usage(name)); return; }
                MusicCommands.help(peer, mode(), args.length == 2 ? args[1] : "").forEach(line -> tell(peer.id(), line));
            } else if (name.equals("reload")) {
                if (args.length != 1) { tell(peer.id(), "用法：" + MusicCommands.usage(name)); return; }
                reloadBridge(peer);
            }
            return;
        }
        MusicServer current = music;
        if (current == null) tell(peer.id(), "音乐服务尚未就绪，请检查插件启动日志。");
        else current.command(peer, args, MusicCommands.Mode.FOLIA);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Folia 26.2 dispatches ordinary Bukkit player completions on the player's entity scheduler.
        MusicServer.Peer peer = sender instanceof Player player ? peer(player)
                : sender instanceof ConsoleCommandSender ? MusicServer.console() : null;
        if (peer == null) return List.of();
        MusicServer current = music;
        return current == null ? MusicCommands.suggest(peer, mode(), args, MusicCommands.Snapshot.EMPTY) : current.suggest(peer, args, mode());
    }
    private void reloadBridge(MusicServer.Peer peer) {
        if (!reloading.compareAndSet(false, true)) { tell(peer.id(), "配置正在重载，请稍候。"); return; }
        tell(peer.id(), "正在重载经济桥接配置……");
        getServer().getAsyncScheduler().runNow(this, task -> {
            try {
                ServerConfig next = ServerConfig.load(directory, config);
                if (isEnabled()) {
                    config = next;
                    tell(peer.id(), "经济桥接配置重载成功，交易记录已保留。频道及收费规则请在 Velocity 执行 /minekumusic reload。");
                }
            } catch (Exception e) { tell(peer.id(), "配置重载失败，当前配置继续生效：" + e.getMessage()); }
            finally { reloading.set(false); }
        });
    }
    private void reconcilePayment(MusicServer.Peer peer, String[] args) {
        if (!peer.admin()) { tell(peer.id(), "需要管理员权限。"); return; }
        if (args.length != 3 || !Set.of("paid", "cancel").contains(args[2].toLowerCase(Locale.ROOT))) { tell(peer.id(), "用法：" + MusicCommands.usage("payment") + "；请先核对经济插件交易记录。"); return; }
        if (!(economy instanceof VaultPayments vault)) { tell(peer.id(), "经济服务不可用，请检查 Vault 和经济插件。"); return; }
        vault.reconcile(args[1], args[2].equalsIgnoreCase("paid")).thenAccept(result -> tell(peer.id(), result.message()));
    }
    @Override public void send(UUID id, List<byte[]> frames) {
        Player player = players.get(id);
        if (player != null) atPlayer(player, () -> {
            if (!player.isOnline()) return;
            for (byte[] frame : frames) player.sendPluginMessage(this, MusicProtocol.CHANNEL, frame);
        });
    }
    @Override public void tell(UUID id, String text) {
        if (id.equals(new UUID(0, 0))) { getLogger().info(text); return; }
        Player player = players.get(id);
        if (player != null) atPlayer(player, () -> { if (player.isOnline()) player.sendMessage("[MineKUmusic] " + text); });
    }
    @Override public void votePrompt(UUID id, String text, String command) {
        Player player = players.get(id);
        Component message = Component.text("[MineKUmusic] " + text + " ")
                .append(Component.text("[\u70b9\u51fb\u6295\u7968\u5207\u6b4c]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text("\u4e3a\u5f53\u524d\u6b4c\u66f2\u6295\u4e00\u7968\uff0c\u6bcf\u4eba\u9650\u4e00\u7968"))));
        if (player != null) atPlayer(player, () -> { if (player.isOnline()) player.sendMessage(message); });
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
