package me.sorihiko.dango.modern

import me.sorihiko.dango.music.ServerMusic
import net.minecraft.client.Minecraft
import net.minecraft.network.Connection

/** A backend transfer replaces the play listener but keeps the proxy socket alive. */
object ServerConnectionTracker {
    private var connection: Connection? = null
    private var playListener: Any? = null
    @JvmStatic fun tick(canSend: Boolean, sender: ServerMusic.Transport) {
        val client = Minecraft.getInstance()
        val listener = client.connection
        val socket = listener?.connection
        if (connection != null && (connection?.isConnected != true || (socket != null && socket !== connection))) {
            ServerMusic.disconnected(); connection = null; playListener = null
        }
        if (socket != null && connection == null) { connection = socket; ServerMusic.connectionOpened() }
        if (listener == null || !canSend) {
            if (playListener != null) { playListener = null; ServerMusic.configurationPhase() }
        } else if (playListener !== listener) {
            playListener = listener; ServerMusic.playReady(sender)
        }
        ServerMusic.tick()
    }
}
