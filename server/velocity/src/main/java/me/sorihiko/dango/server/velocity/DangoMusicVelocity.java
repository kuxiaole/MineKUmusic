package me.sorihiko.dango.server.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.server.*;
import net.kyori.adventure.text.Component;
import com.velocitypowered.api.permission.Tristate;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.*;

@Plugin(id = "minekumusic", name = "MineKUmusic", version = "1.6.4", authors = {"\u5c0f\u4e50 (MineKUmusic)", "Sorihiko (DangoMusic)"})
public final class DangoMusicVelocity implements MusicServer.Endpoint {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(MusicProtocol.CHANNEL);
    private volatile MusicServer music;
    private MusicMessages messages() { MusicServer current = music; return current == null ? MusicMessages.defaults() : current.messages(); }
    private EconomyService economy = EconomyService.UNAVAILABLE;
    @Inject public DangoMusicVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy; this.logger = logger; this.directory = directory;
    }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            Path data = ServerConfig.dataDirectory(directory, "dangomusic-server");
            logger.info("Music data directory: {}", data);
            ServerConfig config = ServerConfig.load(data);
            MusicMessages language = MusicMessages.load(data);
            if (!config.economyBridgeSecret().isEmpty()) economy = new VelocityEconomy(proxy, this, config.economyBridgeSecret());
            else if (config.requestPrice().signum() > 0) logger.warn("Set economy-bridge-secret on Velocity and its economy backends before enabling paid song requests.");
            music = new MusicServer(data, config, new NeteaseProvider(data), this, economy, language);
            proxy.getChannelRegistrar().register(channel);
            proxy.getChannelRegistrar().register(VelocityEconomy.CHANNEL);
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("minekumusic").aliases("music", "kum").plugin(this).build(), new SimpleCommand() {
                @Override public void execute(Invocation invocation) {
                    MusicServer current = music; if (current == null) return;
                    if (invocation.source() instanceof Player player) current.command(peer(player), invocation.arguments(), MusicCommands.Mode.VELOCITY);
                    else if (invocation.source() instanceof ConsoleCommandSource) current.command(MusicServer.console(), invocation.arguments(), MusicCommands.Mode.VELOCITY);
                }
                @Override public List<String> suggest(Invocation invocation) {
                    MusicServer current = music;
                    MusicServer.Peer peer = invocation.source() instanceof Player player ? peer(player)
                            : invocation.source() instanceof ConsoleCommandSource ? MusicServer.console() : null;
                    return current == null || peer == null ? List.of() : current.suggest(peer, invocation.arguments(), MusicCommands.Mode.VELOCITY);
                }
            });
        } catch (Exception e) { logger.error("Could not start MineKUmusic", e); shutdown(null); }
    }
    private boolean allowed(Player player, String node) { return player.getPermissionValue(node) != Tristate.FALSE; }
    private MusicServer.Peer peer(Player player) {
        return new MusicServer.Peer(player.getUniqueId(), player.getUsername(), allowed(player, "dangomusic.use"),
                allowed(player, "dangomusic.request"), allowed(player, "dangomusic.vote"), player.hasPermission("dangomusic.admin"));
    }
    @Subscribe public void message(PluginMessageEvent event) {
        if (VelocityEconomy.CHANNEL.equals(event.getIdentifier())) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            if (economy instanceof VelocityEconomy bridge && event.getSource() instanceof ServerConnection backend) bridge.receive(backend, event.getData());
            return;
        }
        if (!channel.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        MusicServer current = music;
        if (current != null && event.getSource() instanceof Player player) current.receive(peer(player), event.getData());
    }
    @Subscribe public void disconnected(DisconnectEvent event) { MusicServer current = music; if (current != null) current.disconnect(event.getPlayer().getUniqueId()); }
    @Override public void send(UUID id, List<byte[]> frames) {
        if (music == null) return;
        proxy.getPlayer(id).ifPresent(player -> { for (byte[] frame : frames) player.sendPluginMessage(channel, frame); });
    }
    @Override public void tell(UUID id, String text) {
        notice(id, messages().translated(text, true));
    }
    @Override public void notice(UUID id, Component content) {
        chat(id, List.of(MusicChat.notice(messages(), content)));
    }
    @Override public void chat(UUID id, List<Component> lines) {
        if (id.equals(MusicServer.console().id())) lines.forEach(line -> logger.info(MusicChat.plain(line)));
        else proxy.getPlayer(id).ifPresent(player -> lines.forEach(player::sendMessage));
    }
    @Override public void votePrompt(UUID id, String text, String command) {
        if (music == null) return;
        votePrompt(id, messages().render("notice.text", "text", text), command);
    }
    @Override public void votePrompt(UUID id, Component text, String command) {
        if (music == null) return;
        chat(id, List.of(MusicChat.vote(messages(), text, command)));
    }
    @Override public void log(String message, Throwable error) { logger.warn(message, error); }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        MusicServer current = music; music = null; if (current != null) current.close();
        economy.close();
        proxy.getChannelRegistrar().unregister(VelocityEconomy.CHANNEL);
        proxy.getChannelRegistrar().unregister(channel);
        proxy.getCommandManager().unregister("minekumusic");
        proxy.getCommandManager().unregister("music");
        proxy.getCommandManager().unregister("kum");
    }
}
