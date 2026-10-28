package me.sorihiko.dango.music

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class QqDataTest {
    @Test fun qqMetadataKeepsSongAndMediaIdsSeparate() {
        val song = QqApi.parseTrack(JsonParser().parse("""{"mid":"001FixtureSong","name":"Synthetic &amp; Song","interval":123,"file":{"media_mid":"002FixtureMedia"},"album":{"mid":"003FixtureAlbum"},"singer":[{"name":"Fixture Artist"}]}""").asJsonObject)!!
        assertEquals("qq:001FixtureSong", song.key)
        assertEquals("002FixtureMedia", song.mediaId)
        assertEquals("Synthetic & Song", song.name)
        assertEquals(123000, song.durationMs)
        assertTrue(song.id < 0)
    }
    @Test fun qrcRetainsAbsoluteWordTiming() {
        val lines = QqLyrics.parse("[1000,600]Fixture(1000,200) lyric(1200,400)")
        assertEquals("Fixture lyric", lines.single().text)
        assertEquals(1000, lines.single().timeMs)
        assertEquals(listOf(1000L, 1200L), lines.single().words!!.map { it.startMs })
    }
    @Test fun completeMp3ResponseUsesTheProviderAddress() {
        val body = JsonParser().parse("""{"sip":["https://isure.stream.qqmusic.qq.com/"],"midurlinfo":[{"result":0,"purl":"M500002FixtureMedia.mp3?vkey=synthetic"}]}""").asJsonObject
        val audio = QqPlayback().parse(body)!!
        assertFalse(audio.trialOnly)
        assertEquals("https://isure.stream.qqmusic.qq.com/M500002FixtureMedia.mp3?vkey=synthetic", audio.url)
    }
}
