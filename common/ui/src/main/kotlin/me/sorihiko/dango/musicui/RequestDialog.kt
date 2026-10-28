package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.ServerMusic
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import kotlin.math.min

internal class RequestDialog(private val screen: MusicScreen) {
    private val ui = screen.ui
    private var shown: ServerMusic.RequestConfirmation? = null
    private var openedAt = 0L
    fun canConfirm() = shown === ServerMusic.pendingRequest && System.currentTimeMillis() - openedAt >= 200
    fun draw() {
        val request = ServerMusic.pendingRequest ?: return
        if (shown !== request) { shown = request; openedAt = System.currentTimeMillis() }
        ui.hits.clear()
        val progress = ((System.currentTimeMillis() - openedAt) / 180f).coerceIn(0f, 1f)
        val fade = ui.easeOut(progress)
        val width = min(480f, ui.lw - 48f)
        val contentWidth = width - 48f
        val song = I18n.text(if (request.source == "qq") "QQ Music" else "NetEase") + " · " +
            request.songName.ifEmpty { I18n.format("Song ID: %s", request.nativeId) }
        val price = I18n.format("This request costs %s %s.", request.amount, request.currency)
        val priceLines = ui.wrap2(price, contentWidth, 16f)
        val note = if (request.amount.toBigDecimalOrNull()?.signum() == 0) I18n.text("This song request is free.")
            else I18n.text("Paid requests are not refunded when withdrawn or skipped.")
        val noteLines = ui.wrap2(note, contentWidth, 12f)
        val height = 164f + priceLines.size * 22f + noteLines.size * 18f
        val offset = if (screen.reducedChannelMotion) 0f else (1f - fade) * 8f
        val box = LayoutBounds((ui.lw - width) / 2f, (ui.lh - height) / 2f + offset, width, height)
        ui.ctx.pushOpacity(fade)
        ui.ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.45f)))
        ui.ctx.drawGlass(box, Theme.GLASS_WINDOW)
        ui.text(I18n.text("Confirm song request"), box.x + 24f, box.y + 24f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(song, contentWidth, 13f), box.x + 24f, box.y + 58f, 13f, Theme.TEXT_SECONDARY)
        priceLines.forEachIndexed { index, line -> ui.text(line, box.x + 24f, box.y + 90f + index * 22f, 16f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD) }
        val noteY = box.y + 100f + priceLines.size * 22f
        noteLines.forEachIndexed { index, line -> ui.text(line, box.x + 24f, noteY + index * 18f, 12f, Theme.TEXT_SECONDARY) }
        val buttonWidth = (contentWidth - 12f) / 2f
        ui.pill(LayoutBounds(box.x + 24f, box.bottom - 52f, buttonWidth, 32f), Theme.CARD, I18n.text("Cancel")) { ServerMusic.cancelSongRequest() }
        ui.pill(LayoutBounds(box.right - 24f - buttonWidth, box.bottom - 52f, buttonWidth, 32f), RED, I18n.text("Confirm request")) {
            if (canConfirm()) ServerMusic.confirmSongRequest()
        }
        ui.ctx.popOpacity()
    }
}
