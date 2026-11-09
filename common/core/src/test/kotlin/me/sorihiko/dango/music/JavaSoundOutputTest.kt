package me.sorihiko.dango.music

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine

/** Simulated sound card: verifies recovery without a physical device or native sound library. */
class JavaSoundOutputTest {
    private class Device {
        @Volatile var open = true
        @Volatile var running = false
        @Volatile var room = 8
        @Volatile var frames = 0L
        var drainCalls = 0
        val checked = CountDownLatch(1)
        val written = ByteArrayOutputStream()
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "getFormat" -> AudioFormat(44100f, 16, 2, true, false)
                "isOpen" -> open
                "isRunning" -> running
                "getBufferSize" -> 8
                "available" -> { checked.countDown(); room }
                "getLongFramePosition" -> frames
                "getMicrosecondPosition" -> frames * 1000000 / 44100
                "start" -> { running = true; null }
                "stop" -> { running = false; null }
                "close" -> { open = false; null }
                "drain" -> { drainCalls++; null }
                "getControl" -> throw IllegalArgumentException("No hardware gain")
                "write" -> {
                    val data = args!![0] as ByteArray
                    val offset = args[1] as Int
                    val length = args[2] as Int
                    check(length <= room && length % 4 == 0) { "Writing beyond the card's available frames can block forever" }
                    val accepted = minOf(length, 4)
                    written.write(data, offset, accepted)
                    frames += accepted / 4
                    accepted
                }
                else -> null
            }
        } as SourceDataLine
    }

    @Test fun partialWritesPreserveSamplesAndSoftwareVolume() {
        val device = Device()
        val output = JavaSoundOutput(device.line)
        output.start()
        val input = ByteArray(24) { it.toByte() }
        output.write(input, 4, 16)
        assertArrayEquals(input.copyOfRange(4, 20), device.written.toByteArray())
        device.written.reset()
        output.setVolume(0.5f)
        output.write(byteArrayOf(0, 64, 0, -64), 0, 4)
        assertArrayEquals(byteArrayOf(0, 32, 0, -32), device.written.toByteArray())
        assertFalse(output.lost)
        output.close()
    }

    @Test(timeout = 8000) fun stalledDeviceFailsInsteadOfBlockingWritesOrDrain() {
        for (drain in listOf(false, true)) {
            val device = Device().apply { room = 0 }
            val output = JavaSoundOutput(device.line)
            output.start()
            try {
                assertThrows(IllegalStateException::class.java) {
                    if (drain) output.drain() else output.write(ByteArray(4), 0, 4)
                }
                assertTrue(output.lost)
                assertEquals(0, device.drainCalls)
            } finally { output.close() }
        }
    }

    @Test(timeout = 6000) fun pausedOutputWaitsForResumeWithoutDeclaringTheDeviceLost() {
        val device = Device().apply { room = 0 }
        val output = JavaSoundOutput(device.line)
        output.start(); output.stop()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val write = executor.submit { output.write(ByteArray(4), 0, 4) }
            assertTrue(device.checked.await(1, TimeUnit.SECONDS))
            Thread.sleep(2200)
            assertFalse(write.isDone)
            assertFalse(output.lost)
            device.room = 8
            output.start()
            write.get(1, TimeUnit.SECONDS)
            assertEquals(4, device.written.size())
            assertFalse(output.lost)
        } finally { output.close(); executor.shutdownNow() }
    }

    @Test(timeout = 2000) fun closingAFullDeviceReleasesTheWriter() {
        val device = Device().apply { room = 0 }
        val output = JavaSoundOutput(device.line)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val write = executor.submit { output.write(ByteArray(4), 0, 4) }
            assertTrue(device.checked.await(1, TimeUnit.SECONDS))
            output.close()
            write.get(1, TimeUnit.SECONDS)
            assertEquals(0, device.written.size())
            assertFalse(output.lost)
        } finally { output.close(); executor.shutdownNow() }
    }
}
