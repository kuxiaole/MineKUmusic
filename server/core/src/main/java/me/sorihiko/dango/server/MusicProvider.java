package me.sorihiko.dango.server;

import me.sorihiko.dango.protocol.MusicProtocol.Track;
import me.sorihiko.dango.protocol.MusicProtocol.SongRef;
import java.util.List;

public interface MusicProvider extends AutoCloseable {
    record Search(List<Track> tracks, boolean hasNext) {}
    record Audio(Track track, String url, String lrc, String yrc) {}
    default List<String> sources() { return List.of("netease"); }
    default Search search(String query, int page) throws Exception { return search(query, page, me.sorihiko.dango.protocol.MusicProtocol.PAGE_SIZE); }
    Search search(String query, int page, int pageSize) throws Exception;
    Audio resolve(long id) throws Exception;
    default Search search(String source, String query, int page, int pageSize) throws Exception {
        if (!"netease".equals(source)) throw new IllegalArgumentException("Unsupported music source or song ID");
        return search(query, page, pageSize);
    }
    default Audio resolve(SongRef song) throws Exception {
        if (!"netease".equals(song.source)) throw new IllegalArgumentException("Unsupported music source or song ID");
        return resolve(Long.parseLong(song.nativeId));
    }
    @Override default void close() {}
}
