package me.sorihiko.dango.server;

import me.sorihiko.dango.protocol.MusicProtocol.Track;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class MusicMessagesTest {
    @TempDir Path directory;
    private static Stream<Component> parts(Component c) { return Stream.concat(Stream.of(c), c.children().stream().flatMap(MusicMessagesTest::parts)); }
    private static List<ClickEvent<?>> clicks(List<Component> lines) { return lines.stream().flatMap(MusicMessagesTest::parts).map(Component::clickEvent).filter(Objects::nonNull).toList(); }
    private MusicMessages load(String text) throws Exception {
        Files.writeString(directory.resolve("messages.properties"), text);
        return MusicMessages.load(directory);
    }
    @Test void writesChineseDocumentedDefaultsAndRetainsCustomValuesAndComments() throws Exception {
        MusicMessages m = load("# 店主自己的注释\nchat.prefix=<brand>[合成测试服] </brand>\ncolor.brand=#C084FC\ncustom.option=保留\n");
        assertEquals("[合成测试服] ", m.text("chat.prefix"));
        String generated = Files.readString(directory.resolve("messages.properties"));
        String previous = "";
        for (String line : generated.lines().toList()) {
            if (!line.isBlank() && !line.startsWith("#")) assertTrue(previous.startsWith("#") && previous.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), line);
            previous = line;
        }
        assertTrue(generated.contains("custom.option=保留"));
        assertTrue(generated.contains("# 结果行"));
        String edited = "# 我的主题说明，应在普通重载中保留\n" + generated;
        Files.writeString(directory.resolve("messages.properties"), edited);
        assertEquals(m.text("chat.prefix"), MusicMessages.load(directory).text("chat.prefix"));
        assertEquals(edited, Files.readString(directory.resolve("messages.properties")));
    }
    @Test void customColorsLabelsAndHoverKeepTheActualActions() throws Exception {
        MusicMessages m = load("color.action=#67E8F9\nsearch.request=加入全服待播\nsearch.request-hover=<heading>选择：{detail}</heading>\nhelp.title=合成测试服帮助\ncommand.search.description=查找想听的歌\n");
        var peer = new MusicServer.Peer(UUID.randomUUID(), "Fixture", true, true, true, false);
        List<Component> search = MusicChat.search(m, peer, "测试", "synthetic-search", 0, List.of(new Track(101, "歌名", List.of("歌手"), 180000, "")), false);
        Component request = search.stream().flatMap(MusicMessagesTest::parts).filter(c -> ClickEvent.runCommand("/minekumusic request 101").equals(c.clickEvent())).findFirst().orElseThrow();
        assertEquals("[加入全服待播]", MusicChat.plain(request));
        assertEquals(TextColor.fromHexString("#67E8F9"), request.color());
        assertTrue(MusicChat.plain((Component) request.hoverEvent().value()).contains("选择：歌名"));
        assertEquals(List.of(ClickEvent.runCommand("/minekumusic request 101"), ClickEvent.suggestCommand("/minekumusic search ")), clicks(search));
        List<Component> help = MusicCommands.help(m, peer, MusicCommands.Mode.FOLIA, "search");
        assertTrue(help.stream().map(MusicChat::plain).anyMatch(line -> line.contains("合成测试服帮助")));
        assertTrue(help.stream().map(MusicChat::plain).anyMatch(line -> line.contains("查找想听的歌")));
        assertTrue(clicks(help).contains(ClickEvent.suggestCommand("/minekumusic search ")));
    }
    @Test void dynamicTextIsLiteralAndPlaceholderOrderIsCustomizable() throws Exception {
        MusicMessages m = load("search.heading=<heading>{page} / {query} / {query}</heading>\n");
        Component row = m.render("search.heading", "query", "曲名 <red>红</red> {page}", "page", 2);
        assertEquals("2 / 曲名 <red>红</red> {page} / 曲名 <red>红</red> {page}", MusicChat.plain(row));
        assertTrue(parts(row).noneMatch(c -> c.clickEvent() != null));
        assertEquals("<歌曲名称>", load("command.search.arguments=<歌曲名称>\n").value("command.search.arguments"));
    }
    @Test void invalidThemeOrTemplateDoesNotOverwriteTheEditedFile() throws Exception {
        for (String setting : List.of("color.action=mistyped-color", "search.heading=<primary>{qery}</primary>",
                "search.heading=<primary>{query}", "search.heading=<primray>{query}</primray>")) {
            String edited = setting + "\n";
            Files.writeString(directory.resolve("messages.properties"), edited);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MusicMessages.load(directory));
            assertTrue(error.getMessage().contains(setting.substring(0, setting.indexOf('='))), error.getMessage());
            assertEquals(edited, Files.readString(directory.resolve("messages.properties")));
        }
    }
    @Test void independentServerThemesAndEmptyPrefixDoNotChangeDefaults() throws Exception {
        MusicMessages first = load("chat.prefix=\ncolor.action=#112233\nhelp.title=第一个服\n");
        Path secondDirectory = Files.createDirectories(directory.resolve("another-server"));
        Files.writeString(secondDirectory.resolve("messages.properties"), "help.title=第二个服\n");
        MusicMessages second = MusicMessages.load(secondDirectory);
        assertEquals("", first.text("chat.prefix"));
        assertEquals("普通消息", MusicChat.plain(MusicChat.notice(first, Component.text("普通消息"))));
        assertEquals("第一个服", first.text("help.title"));
        assertEquals("第二个服", second.text("help.title"));
        assertEquals("指令帮助", MusicMessages.defaults().text("help.title"));
    }
    @Test void ruleErrorsAreTranslatedButUnrecognizedProviderDetailsRemainVisible() throws Exception {
        MusicMessages m = load("result.the-queue-is-full=<error>合成频道已满，请稍后点歌</error>\n");
        assertEquals("合成频道已满，请稍后点歌", MusicChat.plain(m.translated("The queue is full", false)));
        assertEquals("External fixture detail", MusicChat.plain(m.translated("External fixture detail", false)));
        assertEquals("已撤回歌曲", MusicChat.plain(m.translated("Song withdrawn", true)));
    }
}
