package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.ServerMusic
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.min

internal class ServerLoginDialog(private val screen: MusicScreen) {
    private val ui = screen.ui
    private var texture: Texture? = null
    private var uploaded = ""
    private var opened = ""
    private var openedAt = 0L
    fun release() {
        texture?.let { DangoClient.gfx.textures.delete(it) }; texture = null; uploaded = ""
    }
    fun upload() {
        val login = ServerMusic.serverLogin ?: run { release(); return }
        if (uploaded == login.image) return
        release(); uploaded = login.image
        if (login.image.isEmpty()) return
        try {
            val bytes = Base64.getDecoder().decode(login.image)
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return
                val reader = readers.next()
                try {
                    reader.input = input
                    if (reader.getWidth(0) !in 32..1024 || reader.getHeight(0) !in 32..1024) return
                    texture = DangoClient.gfx.textures.upload("minekumusic_server_qr", reader.read(0))
                } finally { reader.dispose() }
            }
        } catch (_: Exception) { }
    }
    fun draw() {
        val login = ServerMusic.serverLogin ?: return
        if (opened != login.sessionId) { opened = login.sessionId; openedAt = System.currentTimeMillis() }
        ui.hits.clear()
        val fade = ui.easeOut(((System.currentTimeMillis() - openedAt) / 180f).coerceIn(0f, 1f))
        val width = min(370f, ui.lw - 32f)
        val height = min(418f, ui.lh - 24f)
        val box = LayoutBounds((ui.lw - width) / 2f, (ui.lh - height) / 2f, width, height)
        ui.ctx.pushOpacity(fade)
        ui.ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.45f)))
        ui.ctx.drawGlass(box, Theme.GLASS_WINDOW)
        val source = I18n.text(if (login.source == "qq") "QQ Music" else "NetEase")
        ui.text(ui.trim(I18n.text("Server account login") + " · " + source, width - 40f, 16f), box.x + 20f, box.y + 20f, 16f, Theme.TEXT_PRIMARY)
        val size = min(210f, height - 170f).coerceAtLeast(64f)
        val qrBox = LayoutBounds(box.x + (width - size) / 2f, box.y + 58f, size, size)
        ui.card(qrBox.expand(8f, 8f), Color.WHITE, 8f)
        val expired = System.nanoTime() >= login.expiresAt && login.state in listOf("loading", "waiting", "scanned", "verifying")
        val state = if (expired) "expired" else login.state
        if (texture != null && state in listOf("waiting", "scanned")) ui.ctx.drawImage(qrBox, texture!!)
        val message = I18n.text(when (state) {
            "waiting" -> if (login.source == "qq") "Scan with the QQ Music app" else "Scan with the NetEase Cloud Music app"
            "scanned" -> "Scanned. Confirm on your phone."
            "verifying" -> "Verifying server login..."
            "success" -> "Server account saved"
            "expired" -> "QR code expired"
            "cancelled" -> "Server login cancelled"
            "error" -> "Server login failed. Check the server and retry."
            else -> "Loading QR code..."
        })
        ui.wrap2(message, width - 40f, 12f).forEachIndexed { index, line ->
            ui.text(line, box.x + 20f, qrBox.bottom + 24f + index * 18f, 12f, Theme.TEXT_PRIMARY)
        }
        if (state in listOf("waiting", "scanned")) {
            val seconds = ((login.expiresAt - System.nanoTime()) / 1_000_000_000L).coerceAtLeast(0)
            ui.text(I18n.format("Expires in %s seconds", seconds), box.x + 20f, box.bottom - 76f, 11f, Theme.TEXT_SECONDARY)
        }
        ui.pill(LayoutBounds(box.x + 20f, box.bottom - 48f, width - 40f, 30f), Theme.CARD, I18n.text("Close")) { ServerMusic.closeServerLogin() }
        ui.ctx.popOpacity()
    }
}
