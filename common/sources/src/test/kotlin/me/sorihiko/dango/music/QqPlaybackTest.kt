package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class QqPlaybackTest {
    private fun json(value: String) = JsonParser().parse(value).asJsonObject
    private fun data(rows: String) = json("""{"sip":["https://fixture.invalid/"],"midurlinfo":[$rows]}""")
    private val track get() = QqApi.parseTrack(json("""{"mid":"songMid","file":{"media_mid":"mediaMid"},"name":"Fixture"}"""))!!

    @Test fun defaultServerRequestPrefersHighQualityAndFallsBackToStandard() {
        val playback = QqPlayback { request, _ ->
            val params = request.getAsJsonObject("req_0").getAsJsonObject("param")
            assertEquals(listOf("M800mediaMid.mp3", "M500mediaMid.mp3"), params.getAsJsonArray("filename").map { it.asString })
            JsonObject().apply {
                addProperty("code", 0)
                add("req_0", JsonObject().apply {
                    addProperty("code", 0)
                    add("data", data("""{"result":0,"purl":""},{"result":0,"purl":"M500mediaMid.mp3"}"""))
                })
            }
        }
        assertEquals("https://fixture.invalid/M500mediaMid.mp3", playback.resolve(track, null)!!.url)
    }

    @Test fun losslessUsesFlacWhenAvailableAndMp3WhenMissing() {
        for (flacAvailable in listOf(true, false)) {
            val playback = QqPlayback { request, _ ->
                val params = request.getAsJsonObject("req_0").getAsJsonObject("param")
                assertEquals(listOf("F000mediaMid.flac", "M800mediaMid.mp3", "M500mediaMid.mp3"), params.getAsJsonArray("filename").map { it.asString })
                val flac = if (flacAvailable) "F000mediaMid.flac" else ""
                JsonObject().apply {
                    addProperty("code", 0)
                    add("req_0", JsonObject().apply {
                        addProperty("code", 0)
                        add("data", data("""{"purl":"$flac"},{"purl":"M800mediaMid.mp3"},{"purl":"M500mediaMid.mp3"}"""))
                    })
                }
            }
            val file = if (flacAvailable) "F000mediaMid.flac" else "M800mediaMid.mp3"
            assertEquals("https://fixture.invalid/" + file, playback.resolve(track, null, AudioQuality.LOSSLESS)!!.url)
        }
    }

    @Test fun serverParserRejectsFlacAndAllModesRejectPreviews() {
        val playback = QqPlayback()
        val flac = data("""{"purl":"F000mediaMid.flac"}""")
        assertNull(playback.parse(flac))
        assertNotNull(playback.parse(flac, true))
        for (file in listOf("F000mediaMid.flac", "M800mediaMid.mp3", "M500mediaMid.mp3")) {
            for (marker in listOf("\"is_preview\":1", "\"trial\":1", "\"freeTrialInfo\":{}")) {
                assertNull(playback.parse(data("""{"purl":"$file",$marker}"""), true))
            }
        }
    }
}
