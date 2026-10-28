package me.sorihiko.dango.server;

import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.stream.IntStream;
import static me.sorihiko.dango.protocol.MusicProtocol.PAGE_SIZE;
import static net.kyori.adventure.text.format.NamedTextColor.*;

/** Shared command descriptions and completion rules; snapshots contain no platform objects. */
public final class MusicCommands {
    public enum Mode { FOLIA, VELOCITY, BRIDGE }
    public record QueueItem(String entryId, String requesterId) {}
    public record Snapshot(List<QueueItem> queue, String instance) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), "");
        public Snapshot { queue = List.copyOf(queue); }
    }
    private record Definition(String name, String arguments, String description) {}
    private static final List<Definition> COMMANDS = List.of(
            new Definition("help", "[子命令]", "查看帮助；可查看某条指令的详细用法"),
            new Definition("queue", "[页码]", "查看当前歌曲和待播队列；页码从 1 开始"),
            new Definition("search", "<关键词>", "免费搜索歌曲，点击结果中的按钮点歌或翻页"),
            new Definition("searchpage", "<页码>", "查看最近一次搜索的指定页，也可点击搜索结果下方的翻页按钮"),
            new Definition("request", "<歌曲ID>", "先加入频道，再在客户端确认点歌费用；确认后才会扣款入队"),
            new Definition("withdraw", "<队列条目ID>", "撤回自己的待播歌曲；已支付的点歌费用不自动退还"),
            new Definition("vote", "[播放实例ID]", "投票跳过当前歌曲，达到频道收听人数的 15% 即切歌；通常无需填写参数"),
            new Definition("skip", "", "管理员直接跳过当前歌曲"),
            new Definition("pause", "", "管理员暂停整个频道"),
            new Definition("resume", "", "管理员恢复整个频道"),
            new Definition("remove", "<队列条目ID>", "管理员删除指定待播歌曲；已支付的费用不自动退还"),
            new Definition("clear", "", "管理员停止当前歌曲并清空待播队列；不自动退款"),
            new Definition("reload", "", "管理员重新读取配置；保留队列、播放进度、收听状态和交易记录"),
            new Definition("payment", "<交易ID> <paid|cancel>", "仅在原扣款 Folia 后端核对中断交易：paid 表示已扣款，cancel 表示未扣款；务必先核对经济插件记录")
    );
    private static final Set<String> ADMIN = Set.of("skip", "pause", "resume", "remove", "clear", "reload", "payment");
    private MusicCommands() {}
    public static String name(String[] args) { return args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT); }
    public static boolean known(String command, Mode mode) {
        return COMMANDS.stream().anyMatch(c -> c.name.equals(command))
                && (mode != Mode.BRIDGE || Set.of("help", "reload", "payment").contains(command))
                && (mode != Mode.VELOCITY || !command.equals("payment"));
    }
    public static boolean allowed(MusicServer.Peer peer, String command, Mode mode) {
        if (!known(command, mode)) return false;
        if (command.equals("help")) return true;
        if (ADMIN.contains(command)) return peer.admin();
        if (!peer.use()) return false;
        if (Set.of("request", "withdraw", "vote").contains(command) && peer.id().equals(MusicServer.console().id())) return false;
        return switch (command) { case "request" -> peer.request(); case "vote" -> peer.vote(); default -> true; };
    }
    public static String usage(String name) {
        return COMMANDS.stream().filter(c -> c.name.equals(name)).findFirst()
                .map(c -> "/minekumusic " + c.name + (c.arguments.isEmpty() ? "" : " " + c.arguments)).orElse("/minekumusic help");
    }
    public static List<Component> help(MusicServer.Peer peer, Mode mode, String topic) {
        String selected = topic.toLowerCase(Locale.ROOT);
        if (!selected.isEmpty() && !allowed(peer, selected, mode)) return List.of(Component.text("没有可用的对应指令，请使用 /minekumusic help 查看你能使用的指令。", RED));
        List<Component> lines = new ArrayList<>();
        lines.add(MusicChat.title("指令帮助"));
        lines.add(Component.text("主指令：/minekumusic  别名：/music、/kum", GRAY));
        boolean console = peer.id().equals(MusicServer.console().id());
        for (Definition command : COMMANDS) if (allowed(peer, command.name, mode) && (selected.isEmpty() || selected.equals(command.name))) {
            Component line = Component.text(usage(command.name), GOLD).append(Component.text(" — " + command.description, YELLOW));
            if (!console) {
                boolean run = Set.of("help", "queue", "vote").contains(command.name);
                String click = "/minekumusic " + command.name + (!run && !command.arguments.isEmpty() ? " " : "");
                line = line.append(Component.space()).append(MusicChat.action(run ? "执行" : "填入指令", click,
                        command.description + (run ? "\n点击执行：" : "\n点击填入聊天输入框：") + click, !run));
            }
            lines.add(line);
        }
        if (selected.equals("reload")) {
            lines.add(Component.text("可热重载：频道名称、队列上限、冷却、同步间隔、收费开关、价格和币名。", GRAY));
            lines.add(Component.text("server-id、economy-bridge-only、economy-bridge-secret 变更需重启；配置错误时继续使用原配置。", GRAY));
        }
        if (mode == Mode.BRIDGE) lines.add(Component.text("当前为经济桥接后端，音乐队列由 Velocity 管理；请在代理端执行音乐指令。", GRAY));
        else lines.add(Component.text("加入或退出收听请在客户端“全服音乐”页面操作。Tab 可补全子命令、页码和待播条目 ID。", GRAY));
        return List.copyOf(lines);
    }
    public static List<String> suggest(MusicServer.Peer peer, Mode mode, String[] args, Snapshot state) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> candidates;
        if (args.length <= 1 || args.length == 2 && name(args).equals("help")) {
            candidates = COMMANDS.stream().map(Definition::name).filter(c -> allowed(peer, c, mode)).toList();
        } else if (!allowed(peer, name(args), mode)) return List.of();
        else if (args.length == 3 && name(args).equals("payment")) candidates = List.of("paid", "cancel");
        else if (args.length == 2) candidates = switch (name(args)) {
            case "queue" -> IntStream.rangeClosed(1, Math.max(1, (state.queue.size() + PAGE_SIZE - 1) / PAGE_SIZE)).mapToObj(Integer::toString).toList();
            case "withdraw" -> state.queue.stream().filter(e -> e.requesterId.equals(peer.id().toString())).map(QueueItem::entryId).toList();
            case "remove" -> state.queue.stream().map(QueueItem::entryId).toList();
            case "vote" -> state.instance.isEmpty() ? List.of() : List.of(state.instance);
            default -> List.of();
        };
        else return List.of();
        return candidates.stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
