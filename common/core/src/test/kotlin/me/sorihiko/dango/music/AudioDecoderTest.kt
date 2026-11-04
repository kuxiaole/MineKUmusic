package me.sorihiko.dango.music

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sin

/** Two seconds of locally generated sine waves; no provider or audio device is required. */
class AudioDecoderTest {
    private val fixtures = listOf("stereo16.flac", "stereo24.flac", "stereo.ogg")
    private fun bytes(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.use { it.readBytes() }

    @Test fun losslessAndVorbisDecodeStereoWithCorrectDurationAndSignal() {
        for (name in fixtures) {
            val data = bytes(name)
            assertEquals(name, 2000L, AudioAnalysis.duration(data) { true })
            var sample = 0
            var error = 0.0
            AudioDecoder.open(data).use { decoder ->
                while (true) {
                    val frame = decoder.read() ?: break
                    assertEquals(24000, frame.rate)
                    assertEquals(2, frame.channels)
                    for (i in 0 until frame.length) {
                        val time = (sample / 2).toDouble() / 24000
                        val expected = 0.2 * sin(2 * Math.PI * (if (sample % 2 == 0) 440 else 880) * time)
                        error += abs(frame.samples[i] / 32768.0 - expected)
                        sample++
                    }
                }
            }
            assertEquals(name, 96000, sample)
            assertTrue("$name average PCM error: ${error / sample}", error / sample < 0.008)
            val gain = AudioAnalysis.gain(data) { true }
            assertNotNull(name, gain)
            assertEquals(name, 0.884f, gain!!, 0.04f)
        }
    }

    @Test fun seekingBeyondCatalogDurationRecoversAudioAndCancellationStopsLoading() {
        for (name in fixtures) {
            val data = bytes(name)
            val started = AudioDecoder.start(data, 90000) { true }
            started.decoder.use { assertNotNull(name, started.first) }
            assertTrue(name, started.skippedMs < 2000)
            assertThrows(CancellationException::class.java) { AudioDecoder.start(data, 1000) { false } }
            assertThrows(CancellationException::class.java) { AudioAnalysis.duration(data) { false } }
            assertThrows(CancellationException::class.java) { AudioAnalysis.gain(data) { false } }
        }
    }

    @Test fun malformedAudioDoesNotProduceAUsableFrameOrDuration() {
        val data = "This is not an audio stream".toByteArray()
        assertEquals(0L, AudioAnalysis.duration(data) { true })
        AudioDecoder.open(data).use { assertNull(it.read()) }
        assertNull(AudioAnalysis.gain(data) { true })
    }
}
