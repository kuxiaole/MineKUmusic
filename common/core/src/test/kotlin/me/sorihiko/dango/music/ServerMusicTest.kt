package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.protocol.MusicProtocol
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ServerMusicTest {
    companion object {
        @BeforeClass @JvmStatic fun initialize() {
            val directory = Files.createTempDirectory("dango-channel-test-")
            val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, method, _ ->
                when (method.name) {
                    "getConfigDir" -> directory
                    "getPlayerName" -> "Synthetic test player"
                    "getLanguageCode" -> "en_us"
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Float.TYPE -> 0f
                        else -> null
                    }
                }
            } as Platform
            DangoMusic.start(platform, emptyList())
        }
    }
    private val sent = mutableListOf<JsonObject>()
    private val assembler = MusicProtocol.Assembler()
    private var network = ""
    private val transport = ServerMusic.Transport { bytes -> assembler.accept(bytes)?.let { sent.add(it) }; true }
    @Before fun reset() {
        ServerMusic.disconnected(); MusicPlayer.restoreQueue(emptyList(), -1, 0, false, false)
        network = UUID.randomUUID().toString(); sent.clear()
    }
    private fun deliver(type: String, body: JsonObject, rid: String = "") {
        body.addProperty("serverId", network)
        MusicProtocol.frames(MusicProtocol.message(type, rid, body)).forEach { ServerMusic.receive(it) }
    }
    private fun welcome(epoch: String = "first", confirmation: Boolean = false) {
        ServerMusic.connectionOpened(); ServerMusic.playReady(transport)
        deliver("welcome", JsonObject().apply {
            addProperty("epoch", epoch); addProperty("channelName", "Test channel"); addProperty("playerId", "player")
            addProperty("canRequest", true); addProperty("canVote", true)
            addProperty("requestConfirmation", confirmation)
        })
    }
    private fun join() {
        ServerMusic.join()
        val rid = sent.last { it.get("type").asString == "subscribe" }.get("requestId").asString
        deliver("result", JsonObject().apply { addProperty("ok", true); addProperty("message", "Joined server music") }, rid)
    }
    @Test fun firstVisitDoesNotAutoJoin() { welcome(); assertTrue(ServerMusic.available); assertFalse(ServerMusic.joined); assertFalse(sent.any { it.get("type").asString == "subscribe" }) }
    @Test fun leavingRestoresAnInactivePersonalQueue() {
        val track = NeteaseTrack(101, "Synthetic personal track", listOf("Fixture artist"), 180000, "")
        MusicPlayer.restoreQueue(listOf(track), 0, 0, false, false)
        welcome(); join(); assertTrue(ServerMusic.joined)
        ServerMusic.leave(); assertFalse(ServerMusic.joined); assertEquals(101, MusicPlayer.queue.single().id); assertFalse(MusicPlayer.active)
    }
    @Test fun backendConfigurationAndRehandshakeKeepSubscription() {
        welcome(); join(); ServerMusic.configurationPhase()
        assertTrue(ServerMusic.joined)
        welcome(); assertTrue(ServerMusic.joined)
        ServerMusic.disconnected(); assertFalse(ServerMusic.joined); assertFalse(ServerMusic.available)
    }
    @Test fun personalSelectionRequiresConfirmation() {
        welcome(); join()
        ServerMusic.playPersonal(emptyList(), 0)
        assertTrue(ServerMusic.joined); assertNotNull(ServerMusic.pendingPersonal)
        ServerMusic.cancelPersonal(); assertTrue(ServerMusic.joined); assertNull(ServerMusic.pendingPersonal)
    }
    @Test fun pauseIsLocalAndNextSendsVote() {
        welcome(); join()
        deliver("snapshot", JsonObject().apply { addProperty("revision", 1); addProperty("instance", "fixture-current-song") })
        sent.clear()
        ServerMusic.togglePause(); assertTrue(ServerMusic.locallyPaused); assertTrue(sent.isEmpty())
        ServerMusic.previous(); ServerMusic.seek(5000); assertTrue(sent.isEmpty())
        ServerMusic.next(); assertEquals("vote", sent.single().get("type").asString)
        assertEquals("fixture-current-song", sent.single().getAsJsonObject("body").get("instance").asString)
    }
    @Test fun snapshotsIgnoreOlderRevisionAndRestartResetsIt() {
        welcome()
        deliver("snapshot", JsonObject().apply { addProperty("revision", 10); addProperty("listeners", 4) })
        deliver("snapshot", JsonObject().apply { addProperty("revision", 9); addProperty("listeners", 2) })
        assertEquals(4, ServerMusic.listeners)
        welcome("restarted")
        deliver("snapshot", JsonObject().apply { addProperty("revision", 0); addProperty("listeners", 1) })
        assertEquals(1, ServerMusic.listeners)
    }
    @Test fun subscriptionPreferenceBelongsToNetworkId() {
        welcome(); join()
        ServerMusic.disconnected(); sent.clear(); welcome()
        assertTrue(sent.any { it.get("type").asString == "subscribe" })
        ServerMusic.disconnected(); network = UUID.randomUUID().toString(); sent.clear(); welcome()
        assertFalse(sent.any { it.get("type").asString == "subscribe" })
    }
    @Test fun serverPauseFreezesPositionAndDisconnectClearsClock() {
        welcome()
        val track = MusicProtocol.Track(101, "Synthetic clock song", listOf("Fixture"), 180000, "")
        deliver("snapshot", JsonObject().apply {
            addProperty("revision", 1); addProperty("instance", "clock")
            add("current", MusicProtocol.GSON.toJsonTree(MusicProtocol.Entry(track, "a", "A")))
            addProperty("positionMs", 5000); addProperty("paused", true); addProperty("ready", true)
        })
        assertEquals(5000L, ServerMusic.expectedPosition())
        assertEquals(5000L, CompletableFuture.supplyAsync { ServerMusic.expectedPosition() }.get())
        ServerMusic.disconnected(); assertEquals(0L, ServerMusic.expectedPosition())
    }
    private fun quote(expires: Long = 60000) {
        val rid = sent.last { it.get("type").asString == "quoteRequest" }.get("requestId").asString
        deliver("requestQuote", JsonObject().apply {
            addProperty("quoteId", "synthetic-quote"); addProperty("songId", 101)
            addProperty("amount", "12.5"); addProperty("currencyName", "KU\u5e01"); addProperty("expiresInMs", expires)
        }, rid)
    }
    @Test fun paidRequestShowsServerPriceAndWaitsForConfirmation() {
        welcome(confirmation = true); join(); sent.clear()
        ServerMusic.requestSong(101, "Synthetic selection")
        assertEquals("quoteRequest", sent.single().get("type").asString)
        quote()
        assertEquals("12.5", ServerMusic.pendingRequest?.amount)
        assertEquals("KU\u5e01", ServerMusic.pendingRequest?.currency)
        assertEquals("Synthetic selection", ServerMusic.pendingRequest?.songName)
        sent.clear(); ServerMusic.confirmSongRequest(); ServerMusic.confirmSongRequest()
        assertEquals("confirmRequest", sent.single().get("type").asString)
        assertEquals("synthetic-quote", sent.single().getAsJsonObject("body").get("quoteId").asString)
        assertFalse(sent.single().getAsJsonObject("body").has("amount")); assertNull(ServerMusic.pendingRequest)
    }
    @Test fun cancellingTheDialogSendsNoConfirmedRequest() {
        welcome(confirmation = true); join(); ServerMusic.requestSong(101); quote(); sent.clear()
        ServerMusic.cancelSongRequest()
        assertEquals("cancelRequest", sent.single().get("type").asString); assertNull(ServerMusic.pendingRequest)
    }
    @Test fun freeLegacyServerAlsoGetsALocalConfirmation() {
        welcome(); join(); sent.clear(); ServerMusic.requestSong(101)
        assertTrue(sent.isEmpty()); assertEquals("0", ServerMusic.pendingRequest?.amount)
        ServerMusic.confirmSongRequest(); assertEquals("request", sent.single().get("type").asString)
    }
    @Test fun backendTransferKeepsTheQuoteButDisconnectDropsIt() {
        welcome(confirmation = true); join(); ServerMusic.requestSong(101); quote()
        val original = ServerMusic.pendingRequest
        ServerMusic.configurationPhase(); welcome(confirmation = true)
        assertSame(original, ServerMusic.pendingRequest)
        ServerMusic.disconnected(); assertNull(ServerMusic.pendingRequest)
    }
    @Test fun expiredQuoteClosesWithoutSendingConfirmation() {
        welcome(confirmation = true); join(); ServerMusic.requestSong(101); quote(0); sent.clear()
        ServerMusic.confirmSongRequest()
        assertTrue(sent.isEmpty()); assertNull(ServerMusic.pendingRequest)
    }
    @Test fun pendingPaymentStaysActiveUntilTheFinalReceipt() {
        welcome(confirmation = true); join(); ServerMusic.requestSong(101); quote(); ServerMusic.confirmSongRequest()
        val rid = sent.last { it.get("type").asString == "confirmRequest" }.get("requestId").asString
        deliver("result", JsonObject().apply { addProperty("ok", false); addProperty("pending", true); addProperty("message", "Synthetic pending receipt") }, rid)
        assertTrue(ServerMusic.requestingSong); assertEquals("Synthetic pending receipt", ServerMusic.requestFeedback)
        deliver("result", JsonObject().apply { addProperty("ok", true); addProperty("message", "Synthetic completed receipt") }, rid)
        assertFalse(ServerMusic.requestingSong); assertEquals("Synthetic completed receipt", ServerMusic.requestFeedback)
    }
}
