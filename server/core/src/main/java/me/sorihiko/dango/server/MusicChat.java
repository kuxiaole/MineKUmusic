package me.sorihiko.dango.server;

import me.sorihiko.dango.protocol.MusicProtocol.Track;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;
import static net.kyori.adventure.text.format.NamedTextColor.*;

/** Shared chat presentation; components are immutable and contain no platform player objects. */
public final class MusicChat {
    public static final int SEARCH_PAGE_SIZE = 10;
    private MusicChat() {}
    public static String plain(Component message) { return PlainTextComponentSerializer.plainText().serialize(message); }
    public static Component title(String text) {
        return Component.text("━━ ", DARK_GRAY).append(Component.text("MineKU Music", LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text(" · " + text, GOLD)).append(Component.text(" ━━", DARK_GRAY));
    }
    public static Component notice(String text) {
        return Component.text("[MineKUmusic] ", LIGHT_PURPLE).append(Component.text(text, YELLOW));
    }
    public static Component action(String label, String command, String hover, boolean suggest) {
        return Component.text("[" + label + "]", AQUA, TextDecoration.UNDERLINED)
                .clickEvent(suggest ? ClickEvent.suggestCommand(command) : ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, YELLOW)));
    }
    public static Component vote(String text, String command) {
        return notice(text).append(Component.space()).append(action("点击投票切歌", command, "为当前歌曲投一票，每人限一票", false));
    }
    public static List<Component> search(MusicServer.Peer peer, String query, String searchId, int page, List<Track> tracks, boolean hasNext) {
        List<Component> lines = new ArrayList<>();
        lines.add(title("搜索结果 · 免费"));
        lines.add(Component.text("关键词：", GRAY).append(Component.text(query, YELLOW))
                .append(Component.text("  第 " + (page + 1) + " 页", GOLD)));
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            String artists = String.join(" / ", track.artists);
            String detail = track.name + "\n歌手：" + artists + "\n歌曲 ID：" + track.id;
            Component row = Component.text((page * SEARCH_PAGE_SIZE + i + 1) + ". ", GRAY)
                    .append(Component.text(shorten(track.name, 30), YELLOW))
                    .append(Component.text(" · " + shorten(artists, 20), GRAY))
                    .append(Component.text("  #" + track.id, DARK_GRAY));
            if (peer.use() && peer.request() && !peer.id().equals(MusicServer.console().id())) {
                row = row.append(Component.space()).append(action("点歌", "/minekumusic request " + track.id,
                        detail + "\n点击后在客户端确认点歌费用，确认前不扣款。", false));
            }
            lines.add(row.hoverEvent(HoverEvent.showText(Component.text(detail, YELLOW))));
        }
        if (tracks.isEmpty()) lines.add(Component.text("没有找到匹配的歌曲，请更换关键词。", GRAY));
        Component previous = page > 0 ? action("上一页", "/minekumusic searchpage " + page + " " + searchId, "查看上一页搜索结果（免费）", false)
                : Component.text("[上一页]", DARK_GRAY);
        Component next = hasNext ? action("下一页", "/minekumusic searchpage " + (page + 2) + " " + searchId, "查看下一页搜索结果（免费）", false)
                : Component.text("[下一页]", DARK_GRAY);
        lines.add(Component.empty().append(previous).append(Component.text("  |  ", DARK_GRAY)).append(next)
                .append(Component.text("  |  ", DARK_GRAY)).append(action("重新搜索", "/minekumusic search ", "在聊天输入框填写新的搜索关键词", true)));
        lines.add(Component.text("搜索与翻页免费；点歌需加入频道，并在客户端确认费用。", GRAY));
        return List.copyOf(lines);
    }
    private static String shorten(String text, int length) {
        String line = text.replaceAll("\s+", " ").strip();
        return line.codePointCount(0, line.length()) <= length ? line : line.substring(0, line.offsetByCodePoints(0, length)) + "…";
    }
}
