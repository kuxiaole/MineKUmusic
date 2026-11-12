package me.sorihiko.dango.music

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class SoundProcessingTest {
    @Test fun invalidTuningValuesUseAudibleDefaults() {
        for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, SoundTuning.clampSpeed(value), 0f)
            assertEquals(0f, SoundTuning.clampSemitones(value), 0f)
            assertEquals(0f, SoundTuning.clampGain(value), 0f)
        }
        assertEquals(0.5f, SoundTuning.clampSpeed(-10f), 0f)
        assertEquals(2f, SoundTuning.clampSpeed(10f), 0f)
    }
    @Test fun flatEqualizerPreservesNormalLevelStereoPcm() {
        val samples = ShortArray(8192) { ((it % 101 - 50) * 200).toShort() }
        val expected = samples.copyOf()
        Equalizer(2, 44100).process(samples, samples.size, FloatArray(10))
        assertArrayEquals(expected, samples)
    }
    @Test fun equalizerPresetsKeepChannelsIndependentAndAvoidClipping() {
        for (preset in EqPresets.ALL) {
            val pcm = ShortArray(44100 * 2) { if (it % 2 == 0) (30000 * sin(2 * Math.PI * 125 * (it / 2) / 44100)).toInt().toShort() else 0 }
            Equalizer(2, 44100).process(pcm, pcm.size, preset.gains)
            assertTrue(preset.name, pcm.indices.filter { it % 2 == 1 }.all { pcm[it] == 0.toShort() })
            assertTrue(preset.name, pcm.any { abs(it.toInt()) > 500 })
            assertTrue(preset.name, pcm.none { abs(it.toInt()) >= 32767 })
        }
    }
    @Test fun timeStretchChangesDurationWithoutChangingPitch() {
        for (speed in floatArrayOf(0.5f, 2f)) {
            val pcm = render(speed, 1f)
            assertTrue("Duration at " + speed + ": " + pcm.size, abs(pcm.size - 44100 * 2 / speed) < 44100 * 0.15)
            assertEquals(440.0, frequency(pcm), 10.0)
        }
    }
    @Test fun pitchShiftChangesPitchWithoutChangingDuration() {
        for (pitch in floatArrayOf(0.5f, 2f)) {
            val pcm = render(1f, pitch)
            assertTrue(abs(pcm.size - 44100 * 2) < 44100 * 0.15)
            assertEquals(440.0 * pitch, frequency(pcm), 12.0)
        }
    }
    private fun render(speed: Float, pitch: Float): ShortArray {
        val stretcher = SoundStretcher(1, 44100).apply { this.speed = speed; this.pitch = pitch }
        val output = ArrayList<Short>()
        repeat(100) { chunk ->
            val input = ShortArray(882) { (10000 * sin(2 * Math.PI * 440 * (chunk * 882 + it) / 44100)).toInt().toShort() }
            val block = stretcher.process(input, input.size)
            for (i in 0 until stretcher.lastLength) output.add(block[i])
        }
        return output.toShortArray()
    }
    private fun frequency(pcm: ShortArray): Double {
        val from = 4410
        val to = pcm.size - 4410
        require(to > from)
        var crossings = 0
        for (i in from + 1 until to) if (pcm[i - 1] <= 0 && pcm[i] > 0) crossings++
        return crossings * 44100.0 / (to - from)
    }
}
