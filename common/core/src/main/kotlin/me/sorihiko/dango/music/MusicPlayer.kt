package me.sorihiko.dango.music

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import me.sorihiko.dango.DangoLogger

import java.io.ByteArrayInputStream
import java.util.Random
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max

/**
 * 内置网易云播放器：下载 mp3 → JLayer 解码 → Java Sound 输出。
 * 播放进度 = 起始偏移 + 声卡实际已经播放的时长，所以歌词同步是精确的，拖动进度条也没问题。
 */
object MusicPlayer {

    enum class State { STOPPED, LOADING, PLAYING, PAUSED, ERROR }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var current: NeteaseTrack? = null
        private set
    @Volatile var message: String = ""
        private set

    @Volatile var queue: List<NeteaseTrack> = emptyList()
        private set
    @Volatile var index = -1
        private set

    @Volatile var shuffle = false
    /** 歌曲自然播完时重播当前曲目；手动切歌仍按队列 / Shuffle 工作。 */
    @Volatile var repeatOne = false
    /** 0..1 */
    @Volatile var volume = 0.5f

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicLoader").apply { isDaemon = true } }
    private val random = Random()

    /** 每次换歌 / 拖动都 +1，旧的解码线程看到代数变了就自己退出 */
    @Volatile private var generation = 0
    @Volatile private var audio: ByteArray? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var seekBaseMs = 0L
    @Volatile private var pausedAtMs = 0L

    val durationMs: Long get() = current?.durationMs ?: 0L

    // ---------------------------------------------------------------- 鼓点检测（文闪用）

    /** (歌曲内时间 ms, 强度 0..1)，解码时根据低频能量突增检测出来 */
    private class Beat(val timeMs: Long, val strength: Float)
    private val beats = ArrayDeque<Beat>()

    /**
     * 当前的"鼓点冲击"强度：最近一个鼓点之后迅速衰减，0..1。
     * posMs 用歌曲内时间（和歌词同一条时间线）。
     */
    fun beatPulse(posMs: Long): Float {
        var best = 0f
        synchronized(beats) {
            for (b in beats) {
                val dt = posMs - b.timeMs
                if (dt < 0 || dt > 600) continue
                best = max(best, b.strength * kotlin.math.exp(-dt / 110f))
            }
        }
        return best
    }

    private fun clearBeats() = synchronized(beats) { beats.clear() }

    val positionMs: Long
        get() = when (state) {
            State.PLAYING -> seekBaseMs + (line?.microsecondPosition ?: 0L) / 1000
            State.PAUSED -> pausedAtMs
            else -> 0L
        }

    val active: Boolean get() = current != null && (state == State.PLAYING || state == State.PAUSED || state == State.LOADING)

    // ---------------------------------------------------------------- 队列控制

    fun playQueue(tracks: List<NeteaseTrack>, startIndex: Int) {
        queue = tracks
        playIndex(startIndex)
    }

    fun playIndex(i: Int) {
        val q = queue
        if (q.isEmpty()) return
        val idx = ((i % q.size) + q.size) % q.size
        index = idx
        val track = q[idx]
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track) }
    }

    /** 一起听：正在跟随房主（歌放完不自动下一首，等房主切） */
    @Volatile var followMode = false

    /**
     * 一起听：跟随房主播放某首歌。
     * 起始位置在下载完成、真正开始播放前才计算（下载要好几秒，提前算会落后）。
     */
    fun playTrackAt(track: NeteaseTrack, startAt: () -> Long, paused: Boolean) {
        queue = listOf(track)
        index = 0
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track, startAt, paused) }
    }

    fun playServerTrack(track: NeteaseTrack, url: String, startAt: () -> Long, paused: () -> Boolean) {
        followMode = true
        queue = listOf(track)
        index = 0
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "Loading..."
        loader.execute { load(gen, track, startAt, directUrl = url, pauseAtStart = paused) }
    }

    fun restoreQueue(tracks: List<NeteaseTrack>, savedIndex: Int, position: Long, paused: Boolean, active: Boolean = true) {
        followMode = false
        queue = tracks
        if (tracks.isEmpty() || savedIndex !in tracks.indices) { stop(); return }
        index = savedIndex
        if (!active) { stop(); return }
        val track = tracks[index]
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        loader.execute { load(gen, track, { position }, paused) }
    }

    /** 一起听：跳到 ms 并设成播放 / 暂停（当前这首已经下载好时用） */
    fun syncTo(ms: Long, play: Boolean) {
        val bytes = audio ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        pausedAtMs = target
        state = if (play) State.PLAYING else State.PAUSED   // 解码线程据此决定声卡是开着还是停着
        val gen = ++generation
        stopLine()
        startDecode(gen, bytes, target)
    }

    fun next() {
        val q = queue
        if (q.isEmpty()) return
        playIndex(if (shuffle && q.size > 1) pickRandom(q.size) else index + 1)
    }

    fun prev() {
        if (positionMs > 3000) {
            seek(0)
            return
        }
        playIndex(index - 1)
    }

    private fun pickRandom(size: Int): Int {
        var r: Int
        do { r = random.nextInt(size) } while (r == index)
        return r
    }

    fun togglePause() {
        when (state) {
            State.PLAYING -> {
                pausedAtMs = positionMs
                state = State.PAUSED
                line?.stop()
            }
            State.PAUSED -> {
                val l = line
                if (l != null) {
                    // 声卡输出只是停住了，接着放就行；解码线程看到状态变回 PLAYING 会继续写数据
                    state = State.PLAYING
                    l.start()
                } else {
                    seek(pausedAtMs)
                }
            }
            else -> {}
        }
    }

    fun seek(ms: Long) {
        val bytes = audio ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        // 暂停时拖动：停在新位置，仍保持暂停
        if (state == State.PAUSED) pausedAtMs = target
        val gen = ++generation
        stopLine()
        startDecode(gen, bytes, target)
    }

    fun stop() {
        ++generation
        stopLine()
        audio = null
        current = null
        state = State.STOPPED
        message = ""
    }

    // ---------------------------------------------------------------- 加载 / 解码

    private fun load(gen: Int, track: NeteaseTrack, startAt: (() -> Long)? = null, paused: Boolean = false, directUrl: String? = null, pauseAtStart: (() -> Boolean)? = null) {
        if (gen != generation) return
        try {
            val url = if (directUrl != null) NeteaseApi.SongUrl(directUrl, false) else NeteaseApi.songUrl(track.id)
            if (gen != generation) return
            if (url == null) return fail(gen, "拿不到播放地址")
            val bytes = if (directUrl != null) ServerAudioDownload.read(url.url) { gen == generation } else NeteaseApi.request(url.url)
            if (gen != generation) return
            if (bytes == null || bytes.size < 10_000) {
                return fail(gen, if (directUrl != null) "Server audio could not be loaded" else if (NeteaseApi.loggedIn) "这首歌没有播放权限" else "需要登录才能播放（VIP 歌曲）")
            }
            audio = bytes
            message = if (url.trialOnly) "仅试听片段" else ""
            val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
            if (pauseAtStart?.invoke() ?: paused) {
                pausedAtMs = start
                state = State.PAUSED
            }
            startDecode(gen, bytes, start)
        } catch (e: Throwable) {
            // 包括 NoClassDefFoundError 之类的 Error：不接住的话线程会悄悄退出，界面一直停在"加载中"
            DangoLogger.warn("Music load failed: {}", e.toString())
            fail(gen, "加载失败：" + describe(e))
        }
    }

    /** 放不了就提示一下，1.5 秒后自动跳下一首 */
    private fun fail(gen: Int, msg: String) {
        if (gen != generation) return
        state = State.ERROR
        message = msg
        loader.execute {
            Thread.sleep(1500)
            if (gen == generation && !followMode && queue.size > 1) next()
        }
    }

    private fun describe(e: Throwable): String {
        val m = e.message?.take(60)
        return e.javaClass.simpleName + if (m.isNullOrBlank()) "" else ": $m"
    }

    /**
     * 打开声卡输出。默认设备打不开时（被别的程序独占、格式不支持等），
     * 依次试系统里其它能输出这个格式的设备。
     */
    private fun openLine(format: AudioFormat, bufferBytes: Int): SourceDataLine {
        var last: Throwable? = null
        try {
            val l = AudioSystem.getSourceDataLine(format)
            try {
                l.open(format, bufferBytes)
            } catch (e: Exception) {
                l.open(format)
            }
            return l
        } catch (e: Throwable) {
            last = e
            DangoLogger.warn("Default audio line failed: {}", e.toString())
        }
        val info = DataLine.Info(SourceDataLine::class.java, format)
        for (mi in AudioSystem.getMixerInfo()) {
            try {
                val mixer = AudioSystem.getMixer(mi)
                if (!mixer.isLineSupported(info)) continue
                val l = mixer.getLine(info) as SourceDataLine
                l.open(format)
                DangoLogger.info("Using audio device: {}", mi.name)
                return l
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的声音输出设备")
    }

    private fun startDecode(gen: Int, bytes: ByteArray, startMs: Long) {
        Thread({ decodeLoop(gen, bytes, startMs) }, "Dango-MusicDecode").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop(gen: Int, bytes: ByteArray, startMs: Long) {
        var out: SourceDataLine? = null
        var finished = false
        try {
            val bitstream = Bitstream(ByteArrayInputStream(bytes))
            val decoder = Decoder()

            // 跳到目标位置：只读帧头不解码，很快
            var skipped = 0f
            while (skipped < startMs) {
                val h = bitstream.readFrame() ?: break
                skipped += h.ms_per_frame()
                bitstream.closeFrame()
            }
            if (gen != generation) return
            seekBaseMs = skipped.toLong()
            clearBeats()
            var decodedMs = skipped
            var lowPass = 0f
            var avgEnergy = 0f
            var lastBeatMs = -1000f

            var appliedVolume = -1f
            while (gen == generation) {
                val header = bitstream.readFrame()
                if (header == null) {
                    finished = true
                    break
                }
                val sb = try {
                    decoder.decodeFrame(header, bitstream) as SampleBuffer
                } catch (_: Exception) {
                    bitstream.closeFrame()
                    continue
                }
                if (out == null) {
                    val format = AudioFormat(header.frequency().toFloat(), 16, sb.channelCount, true, false)
                    out = openLine(format, header.frequency() * sb.channelCount * 2 / 4)   // 约 0.25 秒缓冲
                    if (gen != generation) return
                    line = out
                    if (state == State.PAUSED) {
                        out.stop()
                    } else {
                        out.start()
                        state = State.PLAYING
                    }
                }
                // 暂停：不再往声卡写数据，原地等待。
                // （Java Sound 在输出停止时被写数据会自动重新开始或直接返回，
                //   不这样等的话会一口气把整首歌"读完"，然后误以为放完了跳下一首）
                while (state == State.PAUSED && gen == generation) {
                    if (out.isRunning) out.stop()   // 写数据时可能被自动重新开始，确保停住
                    Thread.sleep(15)
                }
                if (gen != generation) break
                if (volume != appliedVolume) {
                    applyVolume(out, volume)
                    appliedVolume = volume
                }
                val len = sb.bufferLength
                val pcm = sb.buffer

                // 鼓点：低频（底鼓）能量比最近一秒的平均值突然高出很多
                val frameMs = header.ms_per_frame()
                val ch = sb.channelCount.coerceAtLeast(1)
                var energy = 0f
                var k = 0
                while (k + ch - 1 < len) {
                    var mono = 0f
                    for (c in 0 until ch) mono += pcm[k + c].toFloat()
                    mono /= ch * 32768f
                    lowPass += (mono - lowPass) * 0.04f
                    energy += lowPass * lowPass
                    k += ch
                }
                energy /= (len / ch).coerceAtLeast(1)
                if (avgEnergy > 0f && energy > avgEnergy * 1.7f && energy > 2e-4f && decodedMs - lastBeatMs > 170f) {
                    val strength = ((energy / avgEnergy - 1.7f) / 2f + 0.45f).coerceIn(0f, 1f)
                    synchronized(beats) {
                        beats.addLast(Beat(decodedMs.toLong(), strength))
                        while (beats.size > 48) beats.removeFirst()
                    }
                    lastBeatMs = decodedMs
                }
                avgEnergy = if (avgEnergy == 0f) energy else avgEnergy + (energy - avgEnergy) * (frameMs / 1000f).coerceAtMost(1f)
                decodedMs += frameMs
                val data = ByteArray(len * 2)
                for (k in 0 until len) {
                    val v = pcm[k].toInt()
                    data[k * 2] = (v and 0xFF).toByte()
                    data[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(data, 0, data.size)
                bitstream.closeFrame()
            }
            if (finished && gen == generation) {
                out?.drain()
            }
        } catch (e: Throwable) {
            DangoLogger.warn("Music decode failed: {}", e.toString())
            if (gen == generation) {
                state = State.ERROR
                message = "播放失败：" + describe(e)
            }
        } finally {
            if (line === out) line = null
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
        if (finished && gen == generation) {
            if (followMode) {
                // 跟随房主时放完就停在结尾，等房主切下一首
                pausedAtMs = durationMs
                state = State.PAUSED
            } else if (repeatOne) {
                // 复用已下载的音频，从头解码，不重新请求播放地址或下载。
                seek(0L)
            } else {
                next()
            }
        }
    }

    private fun applyVolume(out: SourceDataLine, v: Float) {
        try {
            val gain = out.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val db = if (v <= 0.0001f) gain.minimum else (20.0 * log10(v.toDouble())).toFloat()
            gain.value = db.coerceIn(gain.minimum, gain.maximum)
        } catch (_: Exception) {
        }
    }

    private fun stopLine() {
        val l = line
        line = null
        try {
            l?.stop()
            l?.flush()
            l?.close()
        } catch (_: Exception) {
        }
    }
}
