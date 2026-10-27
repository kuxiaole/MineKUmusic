package me.sorihiko.dango.server;

import com.google.gson.*;
import me.sorihiko.dango.protocol.MusicProtocol;
import me.sorihiko.dango.protocol.MusicProtocol.Track;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Resolves audio using the server account's ordinary playback entitlement. */
public final class NeteaseProvider implements MusicProvider {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Path directory;
    private final URI origin;

    public NeteaseProvider() { this(null); }
    public NeteaseProvider(Path directory) { this(directory, URI.create("https://music.163.com")); }
    NeteaseProvider(Path directory, URI origin) { this.directory = directory; this.origin = origin; }

    private JsonObject get(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com/").GET();
        if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) throw loginRejected();
            if (response.statusCode() != 200) throw new IllegalStateException("Music provider is unavailable");
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IllegalStateException("Music response is too large");
            JsonObject body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            long code = MusicProtocol.number(body, "code", 200);
            if (code == 301 || code == 401 || code == 403) throw loginRejected();
            return body;
        }
    }
    @Override public Search search(String query, int page) throws Exception {
        int offset = page * MusicProtocol.PAGE_SIZE;
        JsonObject response = get("/api/cloudsearch/pc?s=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&type=1&limit=20&offset=" + offset, NeteaseCookie.load(directory));
        if (!response.has("result")) throw new IllegalStateException("Music search failed");
        JsonObject result = response.getAsJsonObject("result");
        List<Track> tracks = new ArrayList<>();
        if (result.has("songs")) for (JsonElement item : result.getAsJsonArray("songs")) tracks.add(track(item.getAsJsonObject()));
        return new Search(tracks, MusicProtocol.number(result, "songCount", 0) > offset + tracks.size());
    }
    @Override public Audio resolve(long id) throws Exception {
        if (id <= 0) throw new IllegalArgumentException("Invalid song ID");
        String cookie = NeteaseCookie.load(directory);
        JsonObject details = get("/api/song/detail?ids=%5B" + id + "%5D", cookie);
        JsonArray songs = details.getAsJsonArray("songs");
        if (songs == null || songs.isEmpty()) throw new IllegalStateException("Song not found");
        Track track = track(songs.get(0).getAsJsonObject());
        JsonObject playback = get("/api/song/enhance/player/url?ids=%5B" + id + "%5D&br=192000", cookie);
        JsonArray data = playback.getAsJsonArray("data");
        if (data == null || data.isEmpty()) throw unavailable(cookie);
        JsonObject audio = data.get(0).getAsJsonObject();
        String url = MusicProtocol.string(audio, "url", "");
        String type = MusicProtocol.string(audio, "type", "");
        boolean trial = audio.has("freeTrialInfo") && !audio.get("freeTrialInfo").isJsonNull();
        if (url.isEmpty() || MusicProtocol.number(audio, "code", 200) != 200) throw unavailable(cookie);
        if (trial) throw new IllegalStateException("NetEase returned only a preview; check the server account's playback rights");
        if (!type.equalsIgnoreCase("mp3") || track.durationMs <= 0)
            throw new IllegalStateException("This song has no complete MP3 available for channel playback");
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalStateException("Unsupported audio address");
        String lrc = "", yrc = "";
        try {
            JsonObject lyrics = get("/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1&yv=-1", cookie);
            lrc = lyric(lyrics, "lrc"); yrc = lyric(lyrics, "yrc");
        } catch (Exception ignored) { /* Lyrics are optional; audio remains playable. */ }
        return new Audio(track, url, lrc, yrc);
    }
    private static IllegalStateException loginRejected() {
        return new IllegalStateException("Server NetEase login expired or was rejected; update the server Cookie");
    }
    private static IllegalStateException unavailable(String cookie) {
        return new IllegalStateException(cookie.isEmpty()
                ? "No complete playback URL; VIP songs require a server NetEase Cookie with playback rights"
                : "Server NetEase account cannot play this song; check login, membership and song availability");
    }
    private String lyric(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonObject() ? MusicProtocol.string(json.getAsJsonObject(field), "lyric", "") : "";
    }
    private Track track(JsonObject song) {
        JsonArray artists = song.has("ar") ? song.getAsJsonArray("ar") : song.getAsJsonArray("artists");
        List<String> names = new ArrayList<>();
        if (artists != null) for (JsonElement artist : artists) names.add(MusicProtocol.string(artist.getAsJsonObject(), "name", ""));
        JsonObject album = song.has("al") ? song.getAsJsonObject("al") : song.getAsJsonObject("album");
        return new Track(MusicProtocol.number(song, "id", 0), MusicProtocol.string(song, "name", ""), names,
                MusicProtocol.number(song, "dt", MusicProtocol.number(song, "duration", 0)),
                album == null ? "" : MusicProtocol.string(album, "picUrl", ""));
    }
    @Override public void close() { http.shutdownNow(); }
}
