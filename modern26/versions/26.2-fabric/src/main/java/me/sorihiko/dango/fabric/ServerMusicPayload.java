package me.sorihiko.dango.fabric;

import me.sorihiko.dango.protocol.MusicProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ServerMusicPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<ServerMusicPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dangomusic", "channel"));
    public static final StreamCodec<FriendlyByteBuf, ServerMusicPayload> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeBytes(value.bytes), buf -> {
                int size = buf.readableBytes();
                if (size > MusicProtocol.MAX_FRAME) { buf.skipBytes(size); return new ServerMusicPayload(new byte[0]); }
                byte[] bytes = new byte[size]; buf.readBytes(bytes); return new ServerMusicPayload(bytes);
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
