package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.protocol.MusicProtocol.Entry;
import me.sorihiko.dango.protocol.MusicProtocol.Track;
import java.util.*;
import java.util.function.LongSupplier;

/** All calls belong to the coordinator executor; no platform objects enter this class. */
public final class QueueEngine {
    public static final class RuleException extends IllegalArgumentException {
        public RuleException(String message) { super(message); }
    }
    private final LongSupplier clock;
    private final int playerLimit, queueLimit;
    private final long cooldownNanos;
    private final List<Entry> queue = new ArrayList<>();
    private final Set<String> listeners = new HashSet<>(), votes = new HashSet<>();
    private final Map<String, Long> lastRequest = new HashMap<>();
    private Entry current;
    private String instance = "";
    private boolean paused, ready;
    private long baseMs, anchorNanos, revision;

    public QueueEngine(LongSupplier clock, int playerLimit, int queueLimit, long cooldownSeconds) {
        this.clock = clock; this.playerLimit = playerLimit; this.queueLimit = queueLimit;
        cooldownNanos = cooldownSeconds * 1_000_000_000L;
    }
    public boolean subscribed(String id) { return listeners.contains(id); }
    public void subscribe(String id) { if (listeners.add(id)) revision++; }
    public boolean unsubscribe(String id) {
        if (listeners.remove(id)) revision++;
        votes.remove(id);
        return voteThresholdReached();
    }
    public void validateRequest(String id, long songId) {
        if (!subscribed(id)) throw new RuleException("Join the channel before requesting a song");
        if (queue.size() >= queueLimit) throw new RuleException("The queue is full");
        if (queue.stream().filter(e -> e.requesterId.equals(id)).count() >= playerLimit)
            throw new RuleException("Your pending-song limit has been reached");
        Long last = lastRequest.get(id);
        if (last != null && clock.getAsLong() - last < cooldownNanos) throw new RuleException("Please wait before requesting another song");
        if (current != null && current.track.id == songId || queue.stream().anyMatch(e -> e.track.id == songId))
            throw new RuleException("This song is already playing or queued");
    }
    public Entry add(String id, String name, Track track) {
        validateRequest(id, track.id);
        return addAccepted(UUID.randomUUID().toString(), id, name, track);
    }
    /** A paid request was validated and reserved before withdrawal, so disconnecting cannot discard it. */
    Entry addAccepted(String entryId, String id, String name, Track track) {
        if (current != null && current.entryId.equals(entryId)) return current;
        for (Entry existing : queue) if (existing.entryId.equals(entryId)) return existing;
        Entry entry = new Entry(track, id, name); entry.entryId = entryId; queue.add(entry);
        lastRequest.put(id, clock.getAsLong()); revision++; return entry;
    }
    public void remove(String id, String entryId, boolean admin) {
        if (!admin && !subscribed(id)) throw new RuleException("Join the channel before withdrawing a song");
        Entry found = queue.stream().filter(e -> e.entryId.equals(entryId)).findFirst()
                .orElseThrow(() -> new RuleException("That pending song no longer exists"));
        if (!admin && !found.requesterId.equals(id)) throw new RuleException("You can only withdraw your own songs");
        queue.remove(found); revision++;
    }
    public boolean vote(String id) {
        if (!subscribed(id)) throw new RuleException("Join the channel before voting");
        if (current == null) throw new RuleException("Nothing is playing");
        if (votes.add(id)) revision++;
        return voteThresholdReached();
    }
    public int votesRequired() { return Math.max(1, (int) ((listeners.size() * 15L + 99) / 100)); }
    private boolean voteThresholdReached() { return current != null && !listeners.isEmpty() && votes.size() >= votesRequired(); }
    public void skip() { current = null; instance = ""; baseMs = 0; ready = false; votes.clear(); revision++; }
    public void clear() { queue.clear(); skip(); }
    public Entry selectNext() {
        if (current == null && !queue.isEmpty()) {
            current = queue.remove(0); instance = UUID.randomUUID().toString();
            baseMs = 0; ready = false; votes.clear(); revision++;
        }
        return current;
    }
    public void ready() { ready = true; anchorNanos = clock.getAsLong(); revision++; }
    public void pause(boolean value) { baseMs = position(); anchorNanos = clock.getAsLong(); paused = value; revision++; }
    public long position() {
        if (current == null) return 0;
        return Math.min(current.track.durationMs, baseMs + (ready && !paused ? Math.max(0, (clock.getAsLong() - anchorNanos) / 1_000_000L) : 0));
    }
    public boolean ended() { return current != null && ready && !paused && position() >= current.track.durationMs; }
    public Entry current() { return current; }
    public String instance() { return instance; }
    public boolean isReady() { return ready; }
    public boolean paused() { return paused; }
    public long revision() { return revision; }
    public List<Entry> queue() { return Collections.unmodifiableList(queue); }
    public int listeners() { return listeners.size(); }
    public int votes() { return votes.size(); }
    public void pruneCooldowns() { lastRequest.entrySet().removeIf(e -> clock.getAsLong() - e.getValue() >= cooldownNanos); }

    public JsonObject state() {
        JsonObject out = new JsonObject();
        out.addProperty("revision", revision); out.addProperty("instance", instance);
        out.add("current", MusicProtocol.GSON.toJsonTree(current));
        out.addProperty("positionMs", position()); out.addProperty("paused", paused);
        out.addProperty("ready", ready); out.addProperty("listeners", listeners.size());
        out.addProperty("votes", votes.size()); out.addProperty("votesRequired", votesRequired());
        out.addProperty("queueSize", queue.size());
        return out;
    }
    public JsonObject save() { JsonObject out = state(); out.add("queue", MusicProtocol.GSON.toJsonTree(queue)); return out; }
    public void restore(JsonObject saved) {
        queue.clear(); current = null; votes.clear(); listeners.clear(); lastRequest.clear();
        if (saved.has("queue")) for (JsonElement item : saved.getAsJsonArray("queue")) {
            Entry entry = MusicProtocol.GSON.fromJson(item, Entry.class);
            if (valid(entry) && queue.size() < queueLimit) queue.add(entry);
        }
        if (saved.has("current") && !saved.get("current").isJsonNull()) {
            Entry entry = MusicProtocol.GSON.fromJson(saved.get("current"), Entry.class);
            if (valid(entry)) current = entry;
        }
        baseMs = current == null ? 0 : Math.max(0, Math.min(current.track.durationMs, MusicProtocol.number(saved, "positionMs", 0)));
        paused = MusicProtocol.bool(saved, "paused", false); ready = false;
        instance = current == null ? "" : UUID.randomUUID().toString(); revision++;
    }
    private boolean valid(Entry entry) {
        return entry != null && entry.track != null && entry.track.id > 0 && entry.track.durationMs > 0
                && entry.entryId != null && entry.requesterId != null;
    }
}
