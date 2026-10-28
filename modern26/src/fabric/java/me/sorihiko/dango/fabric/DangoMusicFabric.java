package me.sorihiko.dango.fabric;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;
import me.sorihiko.dango.modern.ServerConnectionTracker;
import me.sorihiko.dango.music.ServerMusic;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 26.2 / 26.3 共用 Fabric 入口与全服音乐通信。
 * 画世界 / 画界面的时机用 {@link me.sorihiko.dango.fabric.mixin.GameRendererMixin} 挂在原版里。
 */
public class DangoMusicFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModernKeyBindings.register(KeyMappingHelper::registerKeyMapping);
        ModernDango.start();
        PayloadTypeRegistry.clientboundPlay().register(ServerMusicPayload.TYPE, ServerMusicPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ServerMusicPayload.TYPE, ServerMusicPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ServerMusicPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ServerMusic.INSTANCE.receive(payload.bytes())));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            boolean ready = client.getConnection() != null && ClientPlayNetworking.canSend(ServerMusicPayload.TYPE);
            ServerConnectionTracker.tick(ready, bytes -> {
                if (client.getConnection() == null || !ClientPlayNetworking.canSend(ServerMusicPayload.TYPE)) return false;
                ClientPlayNetworking.send(new ServerMusicPayload(bytes)); return true;
            });
            ModernDango.tick();
        });
    }
}
