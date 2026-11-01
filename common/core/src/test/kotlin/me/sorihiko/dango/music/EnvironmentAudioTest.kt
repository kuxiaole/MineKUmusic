package me.sorihiko.dango.music

import me.sorihiko.dango.platform.ListenerEnvironment
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.math.abs

class EnvironmentAudioTest {
    @After fun reset() {
        EnvironmentAudio.enabled = false
        EnvironmentAudio.update(null, null)
        EnvironmentAudio.reset()
    }

    @Test fun disabledEffectsPreservePcmExactly() {
        reset()
        val pcm = shortArrayOf(-32768, 32767, 1234, -1234, 0, 321)
        val expected = pcm.copyOf()
        EnvironmentAudio.process(pcm, pcm.size, 2, 44100, 26f)
        assertArrayEquals(expected, pcm)
    }

    @Test fun underwaterFiltersHighFrequenciesAndLeavingWaterRestoresDryAudio() {
        EnvironmentAudio.enabled = true; EnvironmentAudio.strength = 1f
        EnvironmentAudio.update(ListenerEnvironment(true, false, 0f, 0f), null)
        EnvironmentAudio.reset()
        val input = ShortArray(4096) { if (it % 2 == 0) 12000 else -12000 }
        val filtered = input.copyOf()
        EnvironmentAudio.process(filtered, filtered.size, 1, 44100, 90f)
        assertTrue(filtered.sumOf { abs(it.toInt()) } < input.sumOf { abs(it.toInt()) } / 4)
        EnvironmentAudio.update(null, null); EnvironmentAudio.reset()
        val dry = input.copyOf()
        EnvironmentAudio.process(dry, dry.size, 1, 44100, 90f)
        assertArrayEquals(input, dry)
    }

    @Test fun roomReverbReflectsWallMaterialAndClearsWhenDisabled() {
        fun measure(absorption: Float): RoomAcoustics.Room {
            val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, method, args ->
                when (method.name) {
                    "getPlayer" -> PlayerPose(0.5, 0.5, 0.5, 0f, 0f)
                    "soundAbsorption" -> if (args!!.any { abs(it as Int) >= 4 }) absorption else -1f
                    else -> null
                }
            } as Platform
            RoomAcoustics.tick(platform, false)
            repeat(4) { RoomAcoustics.tick(platform, true) }
            return RoomAcoustics.room!!
        }
        val stone = measure(0.03f); val wool = measure(0.7f)
        assertTrue(stone.enclosure > 0.95f)
        assertTrue(stone.decaySeconds > wool.decaySeconds)
        assertEquals(0.7f, wool.absorption, 0.001f)
        assertTrue(stone.earlyDelayMs in 2f..80f)
        val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, _, _ -> null } as Platform
        RoomAcoustics.tick(platform, false)
        assertNull(RoomAcoustics.room); assertEquals(0, RoomAcoustics.rays.size)
    }
}
