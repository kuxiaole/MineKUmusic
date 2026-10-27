package me.sorihiko.dango.server;

import me.sorihiko.dango.protocol.MusicProtocol.Track;
import java.util.List;

public interface MusicProvider extends AutoCloseable {
    record Search(List<Track> tracks, boolean hasNext) {}
    record Audio(Track track, String url, String lrc, String yrc) {}
    Search search(String query, int page) throws Exception;
    Audio resolve(long id) throws Exception;
    @Override default void close() {}
}
