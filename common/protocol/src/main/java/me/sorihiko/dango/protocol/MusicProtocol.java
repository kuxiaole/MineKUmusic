package me.sorihiko.dango.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class MusicProtocol {
    public static final String CHANNEL = "dangomusic:channel";
    public static final int VERSION = 1;
    public static final int PAGE_SIZE = 20;
    public static final int MAX_FRAME = 24 * 1024;
    private static final int MAGIC = 0x44474d53;
    private static final int HEADER = 21;
    private static final int MAX_MESSAGE = 1024 * 1024;
    private static final AtomicLong IDS = new AtomicLong();
    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private MusicProtocol() {}

    public static final class Track {
        public long id;
        public String name = "";
        public List<String> artists = new ArrayList<>();
        public long durationMs;
        public String coverUrl = "";
        public Track() {}
        public Track(long id, String name, List<String> artists, long durationMs, String coverUrl) {
            this.id = id; this.name = name; this.artists = new ArrayList<>(artists);
            this.durationMs = durationMs; this.coverUrl = coverUrl;
        }
    }

    public static final class Entry {
        public String entryId = "";
        public String requesterId = "";
        public String requesterName = "";
        public Track track;
        public Entry() {}
        public Entry(Track track, String requesterId, String requesterName) {
            this.entryId = UUID.randomUUID().toString(); this.track = track;
            this.requesterId = requesterId; this.requesterName = requesterName;
        }
    }

    public static JsonObject message(String type, String requestId, JsonObject body) {
        JsonObject value = new JsonObject();
        value.addProperty("type", type); value.addProperty("requestId", requestId);
        value.add("body", body);
        return value;
    }
    public static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }
    public static long number(JsonObject obj, String key, long fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : fallback;
    }
    public static boolean bool(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
    }

    public static List<byte[]> frames(JsonObject message) {
        byte[] bytes = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MESSAGE) throw new IllegalArgumentException("Music message is too large");
        int size = MAX_FRAME - HEADER;
        int count = (bytes.length + size - 1) / size;
        long id = IDS.incrementAndGet();
        List<byte[]> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                DataOutputStream data = new DataOutputStream(out);
                data.writeInt(MAGIC); data.writeByte(VERSION); data.writeLong(id);
                data.writeShort(i); data.writeShort(count); data.writeInt(bytes.length);
                data.write(bytes, i * size, Math.min(size, bytes.length - i * size));
                frames.add(out.toByteArray());
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
        return frames;
    }

    public static final class Assembler {
        private final Map<Long, Pending> pending = new HashMap<>();
        public synchronized void clear() { pending.clear(); }
        public synchronized JsonObject accept(byte[] frame) throws IOException {
            long now = System.nanoTime();
            Iterator<Pending> expired = pending.values().iterator();
            while (expired.hasNext()) if (now - expired.next().created > 10000000000L) expired.remove();
            if (frame.length < HEADER || frame.length > MAX_FRAME) throw new IOException("Invalid music frame size");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("Unsupported music protocol");
            long id = in.readLong();
            int index = in.readUnsignedShort(), count = in.readUnsignedShort(), total = in.readInt();
            int size = MAX_FRAME - HEADER;
            if (total < 1 || total > MAX_MESSAGE || count != (total + size - 1) / size || index >= count)
                throw new IOException("Invalid music transfer");
            if (in.available() != Math.min(size, total - index * size)) throw new IOException("Incomplete music frame");
            Pending p = pending.get(id);
            if (p == null) {
                if (pending.size() >= 4) throw new IOException("Too many music transfers");
                p = new Pending(count, total, now); pending.put(id, p);
            }
            if (p.parts.length != count || p.total != total) throw new IOException("Music transfer changed");
            byte[] part = new byte[in.available()]; in.readFully(part);
            if (p.parts[index] == null) { p.parts[index] = part; p.received++; }
            if (p.received != count) return null;
            pending.remove(id);
            ByteArrayOutputStream out = new ByteArrayOutputStream(total);
            for (byte[] item : p.parts) out.write(item);
            try { return new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("Invalid music message", e); }
        }
        private static final class Pending {
            final byte[][] parts; final int total; final long created; int received;
            Pending(int count, int total, long now) { parts = new byte[count][]; this.total = total; created = now; }
        }
    }
}
