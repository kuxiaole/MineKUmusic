package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import me.sorihiko.dango.protocol.MusicProtocol
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/** Network adapters and UI call this on the client thread. */
object ServerMusic {
    fun interface Transport { fun send(frame: ByteArray): Boolean }
    private var transport: Transport? = null
    private val assembler = MusicProtocol.Assembler()
    private val pending = LinkedHashMap<String, Pair<String, Long>>()
    private val settings = Properties()
    private val settingsWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-ChannelSettings").apply { isDaemon = true } }
    private var settingsLoaded = false
    private val settingsFile get() = DangoMusic.instance.clientDir.resolve("server-music.properties").toFile()
    var connected = false; private set
    var available = false; private set
    var joined = false; private set
    @Volatile var locallyPaused = false; private set
    var channelName = "Server Music"; private set
    var serverId = ""; private set
    var playerId = ""; private set
    var canRequest = false; private set
    var canVote = false; private set
    var status = "Not connected to a server"; private set
    var current: MusicProtocol.Entry? = null; private set
    var listeners = 0; private set
    var votes = 0; private set
    var votesRequired = 1; private set
    var queue: List<MusicProtocol.Entry> = emptyList(); private set
    var queuePage = 0; private set
    var queueTotal = 0; private set
    var queueHasNext = false; private set
    var searchResults: List<MusicProtocol.Track> = emptyList(); private set
    var searchPage = 0; private set
    var searchHasNext = false; private set
    var searching = false; private set
    var lyrics: Lyrics? = null; private set
    var pendingPersonal: (() -> Unit)? = null; private set
    private var desired = false
    private var epoch = ""
    private var revision = -1L
    private var queueRevision = -1L
    private var instance = ""
    private var playingInstance = ""
    private var retriedInstance = ""
    private data class PlaybackClock(val position: Long = 0, val sampledAt: Long = 0, val duration: Long = 0, val paused: Boolean = false, val ready: Boolean = false)
    @Volatile private var playbackClock = PlaybackClock()
    private var oneWayMs = 0L
    private var lastPing = 0L
    private var lastReceived = 0L
    private var lastSync = 0L
    private var searchRequest = ""
    private var lastQuery = ""
    private data class Personal(val queue: List<NeteaseTrack>, val index: Int, val position: Long, val paused: Boolean, val active: Boolean)
    private var personal: Personal? = null

    fun connectionOpened() {
        connected = true
        status = "Server music is not available"
    }
    fun playReady(sender: Transport) {
        transport = sender
        lastReceived = System.nanoTime()
        if (!settingsLoaded) {
            settingsLoaded = true
            try { if (settingsFile.exists()) settingsFile.reader(Charsets.UTF_8).use { settings.load(it) } }
            catch (e: Exception) { DangoLogger.warn("Could not read channel preferences: {}", e.toString()) }
        }
        send("hello")
    }
    fun configurationPhase() { transport = null; assembler.clear() }
    fun disconnected() {
        restorePersonal()
        connected = false; available = false; desired = false; transport = null
        assembler.clear(); pending.clear(); current = null; queue = emptyList(); searchResults = emptyList()
        searching = false; serverId = ""; revision = -1; queueRevision = -1; instance = ""
        playbackClock = PlaybackClock(); listeners = 0; votes = 0; queueTotal = 0
        status = "Not connected to a server"
    }
    fun receive(frame: ByteArray) {
        try {
            val message = assembler.accept(frame) ?: return
            lastReceived = System.nanoTime()
            val body = message.getAsJsonObject("body") ?: return
            val type = MusicProtocol.string(message, "type", "")
            val rid = MusicProtocol.string(message, "requestId", "")
            if (type != "welcome" && MusicProtocol.string(body, "serverId", "") != serverId) return
            when (type) {
                "welcome" -> {
                    val nextServer = MusicProtocol.string(body, "serverId", "")
                    val nextEpoch = MusicProtocol.string(body, "epoch", "")
                    if (serverId != nextServer || epoch != nextEpoch) {
                        restorePersonal(); revision = -1; queueRevision = -1; instance = ""; queue = emptyList()
                        serverId = nextServer
                        epoch = nextEpoch
                        desired = settings.getProperty(serverId, "false").toBoolean()
                    }
                    available = true
                    channelName = MusicProtocol.string(body, "channelName", "Server Music")
                    playerId = MusicProtocol.string(body, "playerId", "")
                    canRequest = MusicProtocol.bool(body, "canRequest", false)
                    canVote = MusicProtocol.bool(body, "canVote", false)
                    status = if (joined) "Listening to server music" else "Not joined"
                    if (desired) send("subscribe")
                    requestQueue(queuePage)
                }
                "snapshot" -> {
                    val sent = MusicProtocol.number(body, "sentNanos", 0)
                    if (sent > 0) oneWayMs = ((System.nanoTime() - sent) / 2_000_000L).coerceIn(0, 2000)
                    applyState(body)
                    if (desired && MusicProtocol.bool(body, "subscribed", false)) enter()
                    if (queueRevision != revision && pending.values.none { it.first == "queue" }) requestQueue(queuePage)
                }
                "audio" -> {
                    if (!joined || !desired || !applyState(body)) return
                    if (instance == playingInstance && !MusicProtocol.bool(body, "refresh", false)) return
                    val entry = current ?: return
                    val url = MusicProtocol.string(body, "url", "")
                    if (url.isEmpty()) return
                    val yrc = MusicProtocol.string(body, "yrc", "")
                    val lrc = MusicProtocol.string(body, "lrc", "")
                    val lines = LyricsProvider.parseYrc(yrc).ifEmpty { LyricsProvider.parseLrc(lrc) }
                    lyrics = if (lines.isEmpty()) null else Lyrics(lines, "server")
                    playingInstance = instance
                    MusicPlayer.playServerTrack(toTrack(entry.track), url, { expectedPosition() }, { playbackClock.paused || locallyPaused })
                }
                "queuePage" -> {
                    val receivedRevision = MusicProtocol.number(body, "revision", -1)
                    if (receivedRevision >= queueRevision) {
                        queueRevision = receivedRevision
                        queue = body.getAsJsonArray("entries").map { MusicProtocol.GSON.fromJson(it, MusicProtocol.Entry::class.java) }
                        queuePage = MusicProtocol.number(body, "page", 0).toInt()
                        queueTotal = MusicProtocol.number(body, "total", 0).toInt()
                        queueHasNext = MusicProtocol.bool(body, "hasNext", false)
                    }
                }
                "searchResults" -> if (rid == searchRequest) {
                    searchResults = body.getAsJsonArray("tracks").map { MusicProtocol.GSON.fromJson(it, MusicProtocol.Track::class.java) }
                    searchPage = MusicProtocol.number(body, "page", 0).toInt()
                    searchHasNext = MusicProtocol.bool(body, "hasNext", false); searching = false
                }
                "result" -> {
                    val action = pending[rid]?.first
                    val ok = MusicProtocol.bool(body, "ok", false)
                    val text = MusicProtocol.string(body, "message", "")
                    if (text.isNotEmpty()) status = text
                    if (action == "subscribe") {
                        if (ok && desired) { enter(); remember(true) } else if (!ok) desired = false
                    }
                    if (action == "search" && rid == searchRequest) searching = false
                }
            }
            if (rid.isNotEmpty()) pending.remove(rid)
        } catch (e: Exception) { status = "Incompatible server music response"; DangoLogger.warn("Music channel response: {}", e.toString()) }
    }
    private fun applyState(body: JsonObject): Boolean {
        val rev = MusicProtocol.number(body, "revision", -1)
        if (rev < revision) return false
        revision = rev
        val nextInstance = MusicProtocol.string(body, "instance", "")
        if (instance != nextInstance) {
            instance = nextInstance; playingInstance = ""; retriedInstance = ""; lyrics = null
            if (joined) MusicPlayer.stop()
        }
        current = body.get("current")?.takeUnless { it.isJsonNull }?.let { MusicProtocol.GSON.fromJson(it, MusicProtocol.Entry::class.java) }
        val paused = MusicProtocol.bool(body, "paused", false)
        val ready = MusicProtocol.bool(body, "ready", false)
        val position = MusicProtocol.number(body, "positionMs", 0) + if (!paused && ready) oneWayMs else 0
        playbackClock = PlaybackClock(position, System.nanoTime(), current?.track?.durationMs ?: 0, paused, ready)
        listeners = MusicProtocol.number(body, "listeners", 0).toInt()
        votes = MusicProtocol.number(body, "votes", 0).toInt()
        votesRequired = MusicProtocol.number(body, "votesRequired", 1).toInt()
        queueTotal = MusicProtocol.number(body, "queueSize", queueTotal.toLong()).toInt()
        return true
    }
    fun expectedPosition(): Long {
        val sample = playbackClock
        return (sample.position + if (!sample.paused && sample.ready) (System.nanoTime() - sample.sampledAt) / 1_000_000L else 0)
            .coerceIn(0, sample.duration)
    }
    fun tick() {
        val now = System.nanoTime()
        val expired = pending.filterValues { now - it.second > 60_000_000_000L }.keys
        if (expired.isNotEmpty()) { expired.forEach { pending.remove(it) }; searching = false; status = "Server music request timed out" }
        if (available && transport != null && now - lastPing > 2_000_000_000L) {
            lastPing = now
            send("ping", JsonObject().apply { addProperty("sentNanos", now) })
            if (lastReceived > 0 && now - lastReceived > 30_000_000_000L) {
                restorePersonal(); available = false; status = "Server music is not available"
            }
        }
        if (!available && transport != null && now - lastPing > 5_000_000_000L) { lastPing = now; send("hello") }
        if (!joined || playingInstance.isEmpty() || playingInstance != instance) return
        if (MusicPlayer.state == MusicPlayer.State.ERROR && retriedInstance != instance) {
            retriedInstance = instance
            send("refreshAudio", JsonObject().apply { addProperty("instance", instance) })
        }
        if (now - lastSync < 1_000_000_000L || MusicPlayer.state == MusicPlayer.State.LOADING || MusicPlayer.state == MusicPlayer.State.ERROR) return
        lastSync = now
        val shouldPause = playbackClock.paused || locallyPaused
        if (shouldPause && MusicPlayer.state == MusicPlayer.State.PLAYING) MusicPlayer.togglePause()
        else if (!shouldPause && expectedPosition() < playbackClock.duration &&
            (MusicPlayer.state == MusicPlayer.State.PAUSED || abs(MusicPlayer.positionMs - expectedPosition()) > 750))
            MusicPlayer.syncTo(expectedPosition(), true)
    }
    fun join() {
        if (!available) { status = "Server music is not available"; return }
        desired = true; status = "Joining server music..."; send("subscribe")
    }
    fun leave() {
        desired = false; remember(false); send("unsubscribe"); restorePersonal(); status = "Not joined"
    }
    private fun enter() {
        if (joined) return
        personal = Personal(MusicPlayer.queue.toList(), MusicPlayer.index, MusicPlayer.positionMs, MusicPlayer.state == MusicPlayer.State.PAUSED, MusicPlayer.active)
        MusicPlayer.stop(); MusicPlayer.followMode = true; joined = true; locallyPaused = false
    }
    private fun restorePersonal() {
        val saved = personal; personal = null
        if (joined) {
            MusicPlayer.stop(); MusicPlayer.followMode = false
            if (saved != null) MusicPlayer.restoreQueue(saved.queue, saved.index, saved.position, saved.paused, saved.active)
        }
        joined = false; locallyPaused = false; lyrics = null; playingInstance = ""; pendingPersonal = null
    }
    private fun remember(value: Boolean) {
        if (serverId.isEmpty()) return
        settings.setProperty(serverId, value.toString())
        val copy = Properties().apply { putAll(settings) }; val file = settingsFile
        settingsWriter.execute {
            try { file.parentFile.mkdirs(); file.writer(Charsets.UTF_8).use { copy.store(it, "Dango Music channel preferences") } }
            catch (e: Exception) { DangoLogger.warn("Could not save channel preference: {}", e.toString()) }
        }
    }
    private fun send(type: String, body: JsonObject = JsonObject()): String {
        val sender = transport ?: return ""
        val id = UUID.randomUUID().toString()
        if (pending.size >= 128) return ""
        pending[id] = type to System.nanoTime()
        for (frame in MusicProtocol.frames(MusicProtocol.message(type, id, body))) if (!sender.send(frame)) { pending.remove(id); return "" }
        return id
    }
    fun requestQueue(page: Int) { if (available) send("queue", JsonObject().apply { addProperty("page", page.coerceAtLeast(0)) }) }
    fun search(query: String, page: Int = 0) {
        if (!available || query.isBlank()) return
        lastQuery = query.take(120); searching = true
        searchRequest = send("search", JsonObject().apply { addProperty("query", lastQuery); addProperty("page", page.coerceAtLeast(0)) })
    }
    fun searchPage(page: Int) = search(lastQuery, page)
    fun requestSong(id: Long) {
        if (!joined) { status = "Join the channel before requesting a song"; return }
        if (!canRequest) { status = "You do not have permission to request songs"; return }
        status = "Adding song..."; send("request", JsonObject().apply { addProperty("songId", id) })
    }
    fun withdraw(entryId: String) { send("withdraw", JsonObject().apply { addProperty("entryId", entryId) }) }
    fun vote() { if (joined && canVote) send("vote", JsonObject().apply { addProperty("instance", instance) }) }
    fun togglePause() {
        if (!joined) { MusicPlayer.togglePause(); return }
        locallyPaused = !locallyPaused
        if (locallyPaused && MusicPlayer.state == MusicPlayer.State.PLAYING) MusicPlayer.togglePause()
        else if (!locallyPaused && !playbackClock.paused) MusicPlayer.syncTo(expectedPosition(), true)
    }
    fun next() { if (joined) vote() else MusicPlayer.next() }
    fun previous() { if (!joined) MusicPlayer.prev() }
    fun seek(ms: Long) { if (!joined) MusicPlayer.seek(ms) }
    fun playPersonal(tracks: List<NeteaseTrack>, index: Int) {
        val action = { MusicPlayer.playQueue(tracks, index) }
        if (joined) pendingPersonal = action else action()
    }
    fun confirmPersonal() { val action = pendingPersonal ?: return; leave(); action() }
    fun cancelPersonal() { pendingPersonal = null }
    fun toTrack(track: MusicProtocol.Track) = NeteaseTrack(track.id, track.name, track.artists, track.durationMs, track.coverUrl)
}
