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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class DangoMusicFolia extends JavaPlugin implements Listener, MusicServer.Endpoint {
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private volatile MusicServer music;
    @Override public void onEnable() {
        try {
            var directory = ServerConfig.dataDirectory(getDataFolder().toPath(), "minekumusic", "DangoMusic");
            getLogger().info("Music data directory: " + directory);
            music = new MusicServer(directory, ServerConfig.load(directory), new NeteaseProvider(directory), this);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getMessenger().registerOutgoingPluginChannel(this, MusicProtocol.CHANNEL);
            getServer().getMessenger().registerIncomingPluginChannel(this, MusicProtocol.CHANNEL, (channel, player, bytes) -> {
                byte[] copy = bytes.clone();
                atPlayer(player, () -> {
                    MusicServer current = music;
                    if (current != null) { players.put(player.getUniqueId(), player); current.receive(peer(player), copy); }
                });
            });
            Objects.requireNonNull(getCommand("dgm")).setExecutor(this);
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
        MusicServer current = music; if (current == null) return true;
        if (sender instanceof Player player) atPlayer(player, () -> current.command(peer(player), args));
        else if (sender instanceof ConsoleCommandSender) current.command(MusicServer.console(), args);
        return true;
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
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        HandlerList.unregisterAll((Listener) this); players.clear();
    }
}
