package me.sorihiko.dango.server;

import me.sorihiko.dango.music.*;
import me.sorihiko.dango.protocol.MusicProtocol.*;
import java.nio.file.Path;
import java.util.List;

public final class ChannelMusicProvider implements MusicProvider {
    private final NeteaseProvider netease;
    private final Path directory;
    public ChannelMusicProvider(Path directory) { this.directory = directory; netease = new NeteaseProvider(directory); }
    @Override public List<String> sources() { return List.of("netease", "qq"); }
    @Override public Search search(String query, int page, int size) throws Exception { return netease.search(query, page, size); }
    @Override public Audio resolve(long id) throws Exception { return netease.resolve(id); }
    @Override public Search search(String source, String query, int page, int size) throws Exception {
        if ("netease".equals(source)) return netease.search(query, page, size);
        requireQq(source);
        QqApi.Search found = new QqApi(qqAccount(directory)).search(query, page, size);
        return new Search(found.getTracks().stream().map(ChannelMusicProvider::track).toList(), found.getHasNext());
    }
    @Override public Audio resolve(SongRef song) throws Exception {
        if (song.source.equals("netease")) return netease.resolve(Long.parseLong(song.nativeId));
        requireQq(song.source);
        QqApi api = new QqApi(qqAccount(directory));
        NeteaseTrack details = api.detail(song.nativeId);
        SourceAudio playback = api.audio(details);
        if (playback == null || playback.getTrialOnly() || details.getDurationMs() <= 0)
            throw new IllegalStateException("This song has no complete MP3 available for channel playback");
        List<SourceLyricLine> lyrics;
        try { lyrics = api.lyrics(song.nativeId); } catch (Exception ignored) { lyrics = List.of(); }
        StringBuilder lrc = new StringBuilder(), yrc = new StringBuilder();
        for (SourceLyricLine line : lyrics) {
            long at = line.getTimeMs();
            lrc.append(String.format(java.util.Locale.ROOT, "[%02d:%02d.%03d]%s%n", at / 60000, at / 1000 % 60, at % 1000, line.getText()));
            if (line.getWords() != null && !line.getWords().isEmpty()) {
                long end = line.getWords().stream().mapToLong(word -> word.getStartMs() + word.getDurMs()).max().orElse(at);
                yrc.append('[').append(at).append(',').append(Math.max(0, end - at)).append(']');
                for (SourceLyricWord word : line.getWords()) yrc.append('(').append(word.getStartMs()).append(',').append(word.getDurMs()).append(",0)").append(word.getText());
                yrc.append('\n');
            }
        }
        return new Audio(track(details), playback.getUrl(), lrc.toString(), yrc.toString());
    }
    static NativeAccount qqAccount(Path directory) throws Exception {
        var cookies = ServerCookies.load(directory, "qq");
        String user = CookieValues.cookie(cookies, "qqmusic_uin");
        String key = CookieValues.cookie(cookies, "qqmusic_key");
        if (user.isEmpty() && key.isEmpty()) return null;
        if (!user.matches("[1-9][0-9]{0,18}") || key.isEmpty()) throw new IllegalStateException("Server QQ Music Cookie is incomplete; log in again");
        int type = CookieValues.cookie(cookies, "login_type").equals("2") || key.startsWith("W_X") ? 1 : 2;
        return new NativeAccount(MusicSource.QQ, user, "", "", "", cookies, type, "");
    }
    private static Track track(NeteaseTrack song) { return new Track(new SongRef(song.getSource().getKey(), song.getNativeId()), song.getName(), song.getArtists(), song.getDurationMs(), song.getCoverUrl()); }
    private static void requireQq(String source) { if (!"qq".equals(source)) throw new IllegalArgumentException("Unsupported music source or song ID"); }
    @Override public void close() { netease.close(); }
}
