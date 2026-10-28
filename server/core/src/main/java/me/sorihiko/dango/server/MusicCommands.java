package me.sorihiko.dango.server;

import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.stream.IntStream;
import static me.sorihiko.dango.protocol.MusicProtocol.PAGE_SIZE;

/** Shared command descriptions and completion rules; snapshots contain no platform objects. */
public final class MusicCommands {
    public enum Mode { FOLIA, VELOCITY, BRIDGE }
    public record QueueItem(String entryId, String requesterId) {}
    public record Snapshot(List<QueueItem> queue, String instance) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), "");
        public Snapshot { queue = List.copyOf(queue); }
    }
    private record Definition(String name, boolean arguments) {}
    private static final List<Definition> COMMANDS = List.of(
            new Definition("help", true),
            new Definition("join", false),
            new Definition("queue", true),
            new Definition("search", true),
            new Definition("searchpage", true),
            new Definition("request", true),
            new Definition("withdraw", true),
            new Definition("vote", true),
            new Definition("skip", false),
            new Definition("pause", false),
            new Definition("resume", false),
            new Definition("remove", true),
            new Definition("clear", false),
            new Definition("reload", false),
            new Definition("payment", true)
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
        if (Set.of("join", "request", "withdraw", "vote").contains(command) && peer.id().equals(MusicServer.console().id())) return false;
        return switch (command) { case "request" -> peer.request(); case "vote" -> peer.vote(); default -> true; };
    }
    public static String usage(String name) { return usage(MusicMessages.defaults(), name); }
    public static String usage(MusicMessages m, String name) {
        return COMMANDS.stream().filter(c -> c.name.equals(name)).findFirst()
                .map(c -> "/minekumusic " + c.name + (c.arguments ? " " + m.value("command." + c.name + ".arguments") : "")).orElse("/minekumusic help");
    }
    public static List<Component> help(MusicServer.Peer peer, Mode mode, String topic) {
        return help(MusicMessages.defaults(), peer, mode, topic);
    }
    public static List<Component> help(MusicMessages m, MusicServer.Peer peer, Mode mode, String topic) {
        String selected = topic.toLowerCase(Locale.ROOT);
        if (!selected.isEmpty() && !allowed(peer, selected, mode)) return List.of(m.render("help.unavailable"));
        List<Component> lines = new ArrayList<>();
        lines.add(MusicChat.title(m, "help.title"));
        lines.add(m.render("help.aliases"));
        boolean console = peer.id().equals(MusicServer.console().id());
        for (Definition command : COMMANDS) if (allowed(peer, command.name, mode) && (selected.isEmpty() || selected.equals(command.name))) {
            Component description = m.render("command." + command.name + ".description");
            Component line = m.render("help.row", "usage", usage(m, command.name), "description", description);
            if (!console) {
                boolean run = Set.of("help", "join", "queue", "vote").contains(command.name);
                String click = "/minekumusic " + command.name + (!run && command.arguments ? " " : "");
                line = line.append(Component.space()).append(MusicChat.action(m, m.render(run ? "help.run" : "help.suggest"), click,
                        m.render(run ? "help.run-hover" : "help.suggest-hover", "description", description, "command", click), !run));
            }
            lines.add(line);
        }
        if (selected.equals("reload")) {
            lines.add(m.render("help.reload-settings"));
            lines.add(m.render("help.reload-restart"));
        }
        lines.add(m.render(mode == Mode.BRIDGE ? "help.bridge" : "help.footer"));
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
