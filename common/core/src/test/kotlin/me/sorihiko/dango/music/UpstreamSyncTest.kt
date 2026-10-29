package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.*
import me.sorihiko.dango.module.values.*
import me.sorihiko.dango.platform.*
import org.junit.Assert.*
import org.junit.Test

/** Offline fixtures for the upstream 1.7.0 features retained by MineKUmusic. */
class UpstreamSyncTest {
    @Test fun translationsMatchNearbyLinesWithoutLosingWordTiming() {
        val words = listOf(LyricWord(1000, 400, "Synthetic line"))
        val lines = listOf(LyricLine(1000, "Synthetic line", words), LyricLine(9000, "Untranslated"))
        val result = LyricsProvider.withTranslation(lines, listOf(LyricLine(1100, "合成译文")))
        assertEquals("合成译文", result[0].translation)
        assertSame(words, result[0].words)
        assertNull(result[1].translation)
        assertSame(result[0], LyricSplit.split(result)[0])
    }

    @Test fun placeholderAndIdenticalTranslationsAreIgnored() {
        val lines = listOf(LyricLine(1000, "Synthetic"), LyricLine(5000, "Same"))
        val result = LyricsProvider.withTranslation(lines, listOf(LyricLine(1000, "//"), LyricLine(5000, "Same")))
        assertTrue(result.all { it.translation == null })
    }

    @Test fun mouseBindingsMigrateWithoutDisablingKeyboardOrMiddleButton() {
        val setting = IntValue(-100, 255, Keys.M)
        var native = Keys.mouse(0)
        val link = KeyBindingLink(PlayerKeyBinding(PlayerAction.OPEN, setting), { native }, { native = it }, native)
        assertEquals(Keys.NONE, native)
        assertEquals(Keys.NONE, setting.value)
        native = Keys.M
        assertTrue(link.sync())
        assertEquals(Keys.M, setting.value)
        native = Keys.mouse(1)
        assertTrue(link.sync())
        assertEquals(Keys.NONE, native)
        setting.setValue(Keys.mouse(2))
        assertTrue(link.sync())
        assertEquals(Keys.mouse(2), native)
    }

    @Test fun invalidSliderValueKeepsTheLastUsableValue() {
        val volume = FloatValue(0f, 1f, 0.4f, 2)
        volume.setValue(Float.NaN)
        assertEquals(0.4f, volume.value, 0f)
        volume.setValue(0.75f)
        assertEquals(0.75f, volume.value, 0f)
    }

    @Test fun lyricColorRoundTripsThroughTheConfigFormat() {
        val color = ColorValue(0xFFFFFF)
        color.setValue(ColorValue.parse("#1aF")!!)
        assertEquals("#11AAFF", color.hex())
        assertEquals(color.value, ColorValue.parse(color.hex()))
        assertNull(ColorValue.parse("not a color"))
    }
}
