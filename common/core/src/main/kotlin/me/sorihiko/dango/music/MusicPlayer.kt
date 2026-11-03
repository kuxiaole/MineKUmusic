package me.sorihiko.dango.music

import me.sorihiko.dango.DangoLogger

import java.util.Random
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.max

/**
 * 内置网易云播放器：下载 mp3 → JLayer 解码 → Java Sound 输出（安卓等不带声音库的 Java 改用游戏的 OpenAL，见 [AudioOutput]）。
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
    @Volatile var volume = 0.2f

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicLoader").apply { isDaemon = true } }
    private val radioWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicRadio").apply { isDaemon = true } }

    /** 电台式的队列（私人 FM）：快放完时用它再要几首接在后面；普通歌单是 null */
    @Volatile var radio: (() -> List<NeteaseTrack>)? = null
        private set
    private var radioRevision = 0L
    private val random = Random()

    /** 每次换歌 / 拖动都 +1，旧的解码线程看到代数变了就自己退出 */
    @Volatile private var generation = 0
    @Volatile private var audio: ByteArray? = null
    @Volatile private var line: AudioOutput? = null
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

    /** 最近检测到的鼓点：[0] 时间（歌曲内 ms），[1] 强度 0..1，从早到晚 */
    fun beatSnapshot(): Array<FloatArray> = synchronized(beats) {
        Array(beats.size) { val b = beats.elementAt(it); floatArrayOf(b.timeMs.toFloat(), b.strength) }
    }

    val positionMs: Long
        get() = when (state) {
            State.PLAYING -> seekBaseMs + (line?.positionMicros ?: 0L) / 1000
            State.PAUSED -> pausedAtMs
            else -> 0L
        }

    val active: Boolean get() = current != null && (state == State.PLAYING || state == State.PAUSED || state == State.LOADING)

    // ---------------------------------------------------------------- 队列控制

    @Synchronized @JvmOverloads fun playQueue(tracks: List<NeteaseTrack>, startIndex: Int, radio: (() -> List<NeteaseTrack>)? = null) {
        radioRevision++
        queue = tracks
        this.radio = radio
        playIndex(startIndex)
    }

    @Synchronized fun playIndex(i: Int) {
        val q = queue
        if (q.isEmpty()) return
        val idx = ((i % q.size) + q.size) % q.size
        val track = q[idx]
        index = idx
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track) }
        if (radio != null && idx >= q.size - 2) extendRadio(null)
    }

    /** 电台：再要几首接到队列后面（去掉已经在队列里的），然后执行 [then] */
    private fun extendRadio(then: (() -> Unit)?) {
        val fetch = radio ?: return
        val revision = radioRevision
        radioWorker.execute {
            val needsMore = synchronized(this) {
                if (radioRevision != revision || radio !== fetch) return@execute
                queue.size - index <= 2
            }
            // 已经有好几首没放的就不用再要了（连续调用时只要一次）
            val more = if (needsMore) {
                try { fetch() } catch (e: Exception) {
                    DangoLogger.warn("Radio fetch failed: {}", e.toString())
                    emptyList()
                }
            } else emptyList()
            synchronized(this) {
                if (radioRevision != revision || radio !== fetch) return@execute
                val have = queue.mapTo(HashSet()) { it.key }
                val fresh = more.filter { have.add(it.key) }
                if (fresh.isNotEmpty()) queue = queue + fresh
                then?.invoke()
            }
        }
    }

    /**
     * 歌单还在加载时就开始放了：加载出更多后把队列换成更长的版本。
     * 只在队列还是 [from] 本身、并且 [to] 以它为开头时才换，当前这首和播放位置都不变。
     */
    @Synchronized fun growQueue(from: List<NeteaseTrack>, to: List<NeteaseTrack>) {
        if (queue !== from || radio != null || to.size <= from.size) return
        for (i in from.indices) if (from[i].key != to[i].key) return
        queue = to
    }

    /** 一起听：正在跟随房主（歌放完不自动下一首，等房主切） */
    @Volatile var followMode = false

    /**
     * 一起听：跟随房主播放某首歌。
     * 起始位置在下载完成、真正开始播放前才计算（下载要好几秒，提前算会落后）。
     */
    @Synchronized fun playTrackAt(track: NeteaseTrack, startAt: () -> Long, paused: Boolean) {
        radioRevision++
        queue = listOf(track)
        radio = null
        index = 0
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track, startAt, paused) }
    }

    @Synchronized fun playServerTrack(track: NeteaseTrack, url: String, startAt: () -> Long, paused: () -> Boolean) {
        radioRevision++
        radio = null
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

    @Synchronized fun restoreQueue(tracks: List<NeteaseTrack>, savedIndex: Int, position: Long, paused: Boolean, active: Boolean = true, radio: (() -> List<NeteaseTrack>)? = null) {
        radioRevision++
        this.radio = radio
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
        if (paused) {
            // Wait for explicit play; a failed background load must not skip to a playing track.
            pausedAtMs = position.coerceIn(0L, max(0L, track.durationMs - 1000))
            state = State.PAUSED
            message = ""
            return
        }
        state = State.LOADING
        loader.execute { load(gen, track, { position }, paused) }
    }

    /** 一起听：跳到 ms 并设成播放 / 暂停（当前这首已经下载好时用） */
    @Synchronized fun syncTo(ms: Long, play: Boolean) {
        val bytes = audio ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        pausedAtMs = target
        state = if (play) State.PLAYING else State.PAUSED   // 解码线程据此决定声卡是开着还是停着
        val gen = ++generation
        stopLine()
        startDecode(gen, bytes, target)
    }

    @Synchronized fun next() {
        val q = queue
        if (q.isEmpty()) return
        // 电台按顺序往下放；放到最后一首时先要几首新的再切
        if (radio != null) {
            if (index + 1 < q.size) playIndex(index + 1)
            else { val from = index; extendRadio { if (index == from) playIndex(from + 1) } }
            return
        }
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
                } else if (audio == null) {
                    restoreQueue(queue, index, pausedAtMs, false, radio = radio)
                } else {
                    syncTo(pausedAtMs, true)
                }
            }
            else -> {}
        }
    }

    fun seek(ms: Long) {
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        // 暂停时拖动：停在新位置，仍保持暂停
        if (state == State.PAUSED) pausedAtMs = target
        val bytes = audio ?: return
        val gen = ++generation
        stopLine()
        startDecode(gen, bytes, target)
    }

    @Synchronized fun stop() {
        radioRevision++
        radio = null
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
            val url = if (directUrl != null) NeteaseApi.SongUrl(directUrl, false) else MusicSources.api(track.source).songUrl(track)
            if (gen != generation) return
            if (url == null) return fail(gen, if (track.source == MusicSource.NETEASE) "拿不到播放地址"
                else if (NativeAccounts.get(track.source) != null) "No playable MP3. Your login may have expired, or this song is restricted by membership or region."
                else "No guest MP3 available. Login, payment or regional restrictions may apply.")
            val bytes = if (directUrl != null) ServerAudioDownload.read(url.url) { gen == generation }
                else if (track.source == MusicSource.NETEASE) NeteaseApi.request(url.url)
                else SourceHttp.bytes(url.url, if (track.source == MusicSource.QQ) "https://y.qq.com/" else "https://m.kugou.com/")
            if (gen != generation) return
            if (bytes == null || bytes.size < 10_000) {
                return fail(gen, if (directUrl != null) "Server audio could not be loaded"
                    else if (track.source != MusicSource.NETEASE) "Unable to download this guest MP3. Try another song."
                    else if (NeteaseApi.loggedIn) "这首歌没有播放权限" else "需要登录才能播放（VIP 歌曲）")
            }
            // Cloud list duration units are not reliable. Use compressed audio timing before enabling seeking.
            val measuredDuration = if (track.cloud) AudioAnalysis.duration(bytes) { gen == generation } else 0L
            val measuredGain = if (normalize) AudioAnalysis.gain(bytes) { gen == generation } else null
            if (gen != generation) return
            if (measuredDuration > 0) track.durationMs = measuredDuration
            audio = bytes
            message = when {
                url.trialOnly -> "Preview only: membership is needed for the full song"
                url.replaced -> "The original was unavailable, playing another version"
                else -> ""
            }
            trackGain = if (normalize) measuredGain ?: loudnessGain(url) else 1f
            DangoLogger.info("Audio analysis: source={} cloud={} durationMs={} localGain={} appliedGain={}",
                track.source.key, track.cloud, measuredDuration, measuredGain != null, trackGain)
            val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
            if (pauseAtStart?.invoke() ?: paused) {
                pausedAtMs = start
                state = State.PAUSED
            }
            startDecode(gen, bytes, start)
        } catch (e: Throwable) {
            // 包括 NoClassDefFoundError 之类的 Error：不接住的话线程会悄悄退出，界面一直停在"加载中"
            if (track.source == MusicSource.NETEASE) {
                DangoLogger.warn("Music load failed: {}", e.javaClass.simpleName)
                fail(gen, if (e is SourceAccessException) e.message.orEmpty() else "加载失败：" + describe(e))
            } else {
                DangoLogger.warn("Source {} load failed: {}", track.source.key, e.javaClass.simpleName)
                fail(gen, if (e is SourceAccessException) e.message.orEmpty() else "Playback request failed. Check your connection or log in again.")
            }
        }
    }

    /** 失败跳过独立计时，不占用加载线程；旧请求不能切掉用户新选的歌。 */
    @Synchronized private fun fail(gen: Int, msg: String) {
        if (gen != generation) return
        state = State.ERROR
        message = msg
        if (current?.source != MusicSource.NETEASE) return
        loader.execute {
            Thread.sleep(1500)
            if (gen == generation && !followMode && queue.size > 1) next()
        }
        failureTimer.schedule({
            synchronized(this) {
                if (gen == generation && state == State.ERROR && failedTracks.size < 10 && !followMode && party.role != me.sorihiko.dango.party.ListenParty.Role.MEMBER) {
                    val candidates = queue.indices.filter { queue[it].key !in failedTracks }
                    if (candidates.isNotEmpty()) {
                        val next = if (shuffle) candidates[random.nextInt(candidates.size)]
                            else candidates.firstOrNull { it > index } ?: candidates.first()
                        skipNotice = System.nanoTime()
                        playIndexInternal(next)
                    }
                }
            }
        }, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun describe(e: Throwable): String {
        val m = e.message?.take(60)
        return e.javaClass.simpleName + if (m.isNullOrBlank()) "" else ": $m"
    }

    /**
     * 打开声卡输出。默认设备打不开时（被别的程序独占、格式不支持等），
     * 依次试系统里其它能输出这个格式的设备。
     */
    /** 测试用：-Ddangomusic.openal=true 时电脑上也用游戏的 OpenAL 放歌 */
    private val forceOpenAl = java.lang.Boolean.getBoolean("dangomusic.openal")
    private var loggedOutput = ""

    /**
     * 打开声音输出：Java 自己带声音库时用 Java Sound（电脑上都是这样）；
     * 不带（安卓）或者强制时先用游戏的 OpenAL，打不开再退回 Java Sound；Java Sound 打不开也试一下 OpenAL。
     */
    private fun openOutput(format: AudioFormat, bufferBytes: Int): AudioOutput {
        if (forceOpenAl || !AudioOutput.javaSoundNative) platformOutput(format)?.let { return it }
        return try {
            JavaSoundOutput(openLine(format, bufferBytes)).also { logOutput("Java Sound") }
        } catch (e: Throwable) {
            platformOutput(format) ?: throw e
        }
    }

    private fun platformOutput(format: AudioFormat): AudioOutput? = try {
        me.sorihiko.dango.DangoMusic.instance.platform.openAudioOutput(format.sampleRate.toInt(), format.channels)
            ?.also { logOutput("game OpenAL") }
    } catch (e: Throwable) {
        DangoLogger.warn("Game audio output failed: {}", e.toString())
        null
    }

    private fun logOutput(name: String) {
        if (loggedOutput == name) return
        loggedOutput = name
        DangoLogger.info("Dango Music: playing through {}", name)
    }

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

    /** 音量均衡（设置里开关）：按音源给的响度把每首歌调到差不多响 */
    @Volatile var normalize = true
    /** 这首歌要乘的倍数（1 = 原样） */
    @Volatile private var trackGain = 1f

    private fun loudnessGain(url: NeteaseApi.SongUrl): Float {
        if (!normalize || url.gainDb == 0f) return 1f
        var gain = Math.pow(10.0, url.gainDb / 20.0).toFloat()
        // 有峰值就别把峰值推过 1（会破音）
        if (url.peak > 0f) gain = minOf(gain, 0.98f / url.peak)
        return gain.coerceIn(0.25f, 2f)
    }

    private fun startDecode(gen: Int, bytes: ByteArray, startMs: Long) {
        Thread({ decodeLoop(gen, bytes, startMs) }, "Dango-MusicDecode").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop(gen: Int, bytes: ByteArray, startMs: Long) {
        var out: AudioOutput? = null
        var finished = false
        var source: AudioDecoder? = null
        try {
            val started = AudioDecoder.start(bytes, startMs) { gen == generation }
            val decoder = started.decoder
            source = decoder
            val skipped = started.skippedMs
            val first = started.first
            if (gen != generation) return
            seekBaseMs = skipped.toLong()
            clearBeats()
            EnvironmentAudio.reset()
            var decodedMs = skipped
            var lowPass = 0f
            var avgEnergy = 0f
            var lastBeatMs = -1000f

            var appliedVolume = -1f
            var pending = first
            while (gen == generation) {
                val frame = pending ?: decoder.read()
                pending = null
                if (frame == null) {
                    finished = true
                    break
                }
                if (out == null) {
                    val format = AudioFormat(header.frequency().toFloat(), 16, sb.channelCount, true, false)
                    out = openOutput(format, header.frequency() * sb.channelCount * 2 / 4)   // 约 0.25 秒缓冲
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
                    out.setVolume(volume)
                    appliedVolume = volume
                }
                val len = frame.length
                val pcm = frame.samples

                // 鼓点：低频（底鼓）能量比最近一秒的平均值突然高出很多
                val frameMs = frame.durationMs
                val ch = frame.channels.coerceAtLeast(1)
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
                // 音量均衡、环境音效（水下变闷、洞穴回声……）在鼓点检测之后加，不影响节奏判断
                val gain = trackGain
                if (gain != 1f) for (idx in 0 until len) pcm[idx] = (pcm[idx] * gain).toInt().coerceIn(-32768, 32767).toShort()
                EnvironmentAudio.process(pcm, len, ch, frame.rate, frameMs)
                val data = ByteArray(len * 2)
                for (k in 0 until len) {
                    val v = pcm[k].toInt()
                    data[k * 2] = (v and 0xFF).toByte()
                    data[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(data, 0, data.size)
            }
            if (finished && out == null && gen == generation) {
                finished = false
                fail(gen, "No decodable audio was found.")
            }
            if (finished && gen == generation) {
                out?.drain()
                synchronized(this) { if (gen == generation) failedTracks.clear() }
            }
        } catch (e: Throwable) {
            finished = false
            DangoLogger.warn("Music decode failed: {}", e.toString())
            if (gen == generation) {
                fail(gen, "播放失败：" + describe(e))
            }
        } finally {
            try { source?.close() } catch (_: Exception) {}
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
