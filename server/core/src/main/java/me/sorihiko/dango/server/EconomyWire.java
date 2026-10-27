package me.sorihiko.dango.server;

import com.google.gson.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static me.sorihiko.dango.protocol.MusicProtocol.GSON;
import static me.sorihiko.dango.server.EconomyService.*;

/** This channel is only for the proxy and backend plugins. It is never sent to the music client. */
public final class EconomyWire {
    public static final String CHANNEL = "minekumusic:economy";
    public record Message(String type, Payment payment, Result result) {}
    private final byte[] key;
    public EconomyWire(String secret) {
        if (secret.length() < 32) throw new IllegalArgumentException("economy-bridge-secret must contain at least 32 characters on both proxy and backends");
        key = secret.getBytes(StandardCharsets.UTF_8);
    }
    public byte[] encode(Message message) {
        byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (payload.length > 8000) throw new IllegalArgumentException("Economy message is too large");
        byte[] signature = sign(payload), output = Arrays.copyOf(signature, signature.length + payload.length);
        System.arraycopy(payload, 0, output, signature.length, payload.length); return output;
    }
    public Message decode(byte[] bytes) {
        if (bytes.length <= 32 || bytes.length > 8032) throw new IllegalArgumentException("Invalid economy message");
        byte[] payload = Arrays.copyOfRange(bytes, 32, bytes.length);
        if (!MessageDigest.isEqual(Arrays.copyOf(bytes, 32), sign(payload))) throw new IllegalArgumentException("Invalid economy signature");
        Message message;
        try { message = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), Message.class); }
        catch (JsonParseException | IllegalStateException e) { throw new IllegalArgumentException("Invalid economy message", e); }
        if (message == null || message.payment() == null || !("charge".equals(message.type()) || "receipt".equals(message.type())))
            throw new IllegalArgumentException("Invalid economy message");
        if ("receipt".equals(message.type()) && (message.result() == null || message.result().status() == null
                || message.result().message() == null || message.result().message().length() > 1000))
            throw new IllegalArgumentException("Invalid economy receipt");
        Payment p = message.payment();
        if (p.id() == null) throw new IllegalArgumentException("Invalid transaction ID");
        UUID.fromString(p.id());
        if (p.playerId() == null || p.playerName() == null || p.playerName().length() > 64 || p.backend() == null || p.backend().length() > 128
                || p.amount() == null || p.amount().signum() <= 0 || p.amount().compareTo(new BigDecimal("1000000")) > 0 || p.amount().stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("Invalid payment details");
        return message;
    }
    private byte[] sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data);
        } catch (Exception e) { throw new IllegalStateException("Could not authenticate the economy bridge", e); }
    }
}
