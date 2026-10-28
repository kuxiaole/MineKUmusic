package me.sorihiko.dango.server;

import me.sorihiko.dango.protocol.MusicProtocol.Track;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;

/** Shared chat presentation; components are immutable and contain no platform player objects. */
public final class MusicChat {
    public static final int SEARCH_PAGE_SIZE = 10;
    private MusicChat() {}
    public static String plain(Component message) { return PlainTextComponentSerializer.plainText().serialize(message); }
    public static Component title(MusicMessages m, String key) { return m.render("chat.title", "title", m.render(key)); }
    public static Component notice(MusicMessages m, Component message) {
        return Component.empty().append(m.render("chat.prefix")).append(message);
    }
    public static Component action(MusicMessages m, Component label, String command, Component hover, boolean suggest) {
        return m.render("button.label", "label", label)
                .clickEvent(suggest ? ClickEvent.suggestCommand(command) : ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(hover));
    }
    public static Component vote(MusicMessages m, Component text, String command) {
        return notice(m, text).append(Component.space()).append(action(m, m.render("vote.button"), command, m.render("vote.hover"), false));
    }
    public static Component requestAnnouncement(MusicMessages m, String channel, String player, Track track) {
        Component detail = detail(m, track);
        Component text = m.render("request.announcement", "channel", channel, "player", player,
                "song", shorten(track.name, 30), "artists", shorten(String.join(" / ", track.artists), 20));
        return notice(m, text.hoverEvent(HoverEvent.showText(detail))).append(Component.space())
                .append(action(m, m.render("join.button"), "/minekumusic join", m.render("join.hover"), false));
    }
    public static List<Component> search(MusicServer.Peer peer, String query, String searchId, int page, List<Track> tracks, boolean hasNext) {
        return search(MusicMessages.defaults(), peer, query, searchId, page, tracks, hasNext);
    }
    public static List<Component> search(MusicMessages m, MusicServer.Peer peer, String query, String searchId, int page, List<Track> tracks, boolean hasNext) {
        List<Component> lines = new ArrayList<>();
        lines.add(title(m, "search.title"));
        lines.add(m.render("search.heading", "query", query, "page", page + 1));
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            String artists = String.join(" / ", track.artists);
            Component detail = detail(m, track);
            Component row = m.render("search.row", "index", page * SEARCH_PAGE_SIZE + i + 1,
                    "name", shorten(track.name, 30), "artists", shorten(artists, 20), "id", track.ref().nativeId,
                    "source", source(m, track));
            if (peer.use() && peer.request() && !peer.id().equals(MusicServer.console().id())) {
                String request = track.ref().source.equals("netease") ? track.ref().nativeId : track.ref().source + " " + track.ref().nativeId;
                row = row.append(Component.space()).append(action(m, m.render("search.request"), "/minekumusic request " + request,
                        m.render("search.request-hover", "detail", detail), false));
            }
            lines.add(row.hoverEvent(HoverEvent.showText(detail)));
        }
        if (tracks.isEmpty()) lines.add(m.render("search.empty"));
        Component previous = page > 0 ? action(m, m.render("search.previous"), "/minekumusic searchpage " + page + " " + searchId, m.render("search.previous-hover"), false)
                : m.render("button.disabled", "label", m.render("search.previous"));
        Component next = hasNext ? action(m, m.render("search.next"), "/minekumusic searchpage " + (page + 2) + " " + searchId, m.render("search.next-hover"), false)
                : m.render("button.disabled", "label", m.render("search.next"));
        lines.add(Component.empty().append(previous).append(m.render("button.separator")).append(next)
                .append(m.render("button.separator")).append(action(m, m.render("search.again"), "/minekumusic search ", m.render("search.again-hover"), true)));
        lines.add(m.render("search.footer"));
        return List.copyOf(lines);
    }
    private static Component source(MusicMessages m, Track track) {
        return m.render("source." + track.ref().source);
    }
    private static Component detail(MusicMessages m, Track track) {
        return m.render("search.detail", "name", track.name, "artists", String.join(" / ", track.artists),
                "id", track.ref().nativeId, "source", source(m, track));
    }
    private static String shorten(String text, int length) {
        String line = text.replaceAll("\s+", " ").strip();
        return line.codePointCount(0, line.length()) <= length ? line : line.substring(0, line.offsetByCodePoints(0, length)) + "…";
    }
}
