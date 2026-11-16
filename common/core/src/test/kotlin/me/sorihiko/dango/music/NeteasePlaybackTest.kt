package me.sorihiko.dango.music

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NeteasePlaybackTest {
    @After fun resetQuality() { PlaybackQuality.override = null; PlaybackQuality.default = AudioQuality.HIGH }

    @Test fun unavailableLosslessFallsBackThroughHighToStandard() {
        PlaybackQuality.override = AudioQuality.LOSSLESS
        val levels = mutableListOf<String>()
        val playback = NeteasePlayback({ endpoint, body ->
            assertEquals("v1", endpoint)
            val level = body.get("level").asString
            levels += level
            assertEquals(if (level == "lossless") "flac" else "mp3", body.get("encodeType").asString)
            val url = if (level == "standard") "https://fixture.invalid/song.mp3" else ""
            JsonParser().parse("""{"code":200,"data":[{"id":123,"url":"$url","type":"mp3"}]}""").asJsonObject
        })
        assertEquals("https://fixture.invalid/song.mp3", playback.resolve(123)!!.url)
        assertEquals(listOf("lossless", "exhigh", "standard"), levels)
    }

    @Test fun fullLowerQualityWinsOverLosslessPreview() {
        PlaybackQuality.override = AudioQuality.LOSSLESS
        val levels = mutableListOf<String>()
        val playback = NeteasePlayback({ _, body ->
            val level = body.get("level").asString
            levels += level
            val trial = if (level == "lossless") ",\"freeTrialInfo\":{}" else ""
            val format = if (level == "lossless") "flac" else "mp3"
            JsonParser().parse("""{"code":200,"data":[{"id":123,"url":"https://fixture.invalid/song.$format","type":"$format"$trial}]}""").asJsonObject
        })
        val audio = playback.resolve(123)!!
        assertFalse(audio.trialOnly)
        assertEquals("https://fixture.invalid/song.mp3", audio.url)
        assertEquals(listOf("lossless", "exhigh"), levels)
    }
}
