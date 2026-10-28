package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import kotlin.math.max

internal class AboutPage(screen: MusicScreen) {
    private val ui = screen.ui
    private data class Credit(val title: String, val author: String, val description: String, val url: String = "")
    private val credits = listOf(
        Credit("Project source", "MineKUmusic | \u5c0f\u4e50 | GPL-3.0",
            "Source code and issue tracker for MineKUmusic. Use the button to copy the repository link.",
            "https://github.com/kuxiaole/MineKUmusic"),
        Credit("DangoMusic", "Sorihiko | GPL-3.0",
            "Client foundation: multi-source personal music, account login, playlists, lyrics, HUD and interface. MineKU Music is a modified version of this project.",
            "https://github.com/Sorihiko/DangoMusic"),
        Credit("AllMusic", "Coloryr | GPL-3.0",
            "Server design reference: shared queue, song requests, client playback and Velocity integration. Uses a separate music protocol.",
            "https://github.com/Coloryr/AllMusic"),
        Credit("Upstream design credit", "MuoniumPlayer",
            "DangoMusic's original interface comments credit MuoniumPlayer as a layout reference."),
        Credit("License", "GPL-3.0",
            "Original author credits and license notices are retained. Full license texts and CREDITS.md are included in the distribution."),
        Credit("Bundled components", "Inter | JLayer | ZXing | Kotlin",
            "Fonts, MP3 decoding, login QR codes and Kotlin runtime retain their respective open-source licenses."),
    )
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f
    private var copiedUrl = ""
    private var copiedUntil = 0L

    fun tick() { pos = ui.approach(pos, target, 70f) }
    fun resetScroll() { target = 0f; pos = 0f }
    fun scroll(wheel: Int) { target = (target + if (wheel > 0) -60f else 60f).coerceIn(0f, maxScroll) }

    fun draw(view: LayoutBounds) {
        if (view.width <= 0f || view.height <= 0f) return
        val width = max(1f, view.width - 24f)
        val subtitle = ui.wrap2(I18n.format("This version is developed by %s.", "\u5c0f\u4e50"), width, 12f)
        val headerHeight = 56f + subtitle.size * 18f
        val descriptions = credits.map { ui.wrap2(I18n.text(it.description), width, 12f) }
        val heights = descriptions.mapIndexed { index, lines -> 56f + lines.size * 18f + if (credits[index].url.isEmpty()) 0f else 32f }
        val contentHeight = headerHeight + heights.sum()
        maxScroll = max(0f, contentHeight - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)
        ui.beginClip(view)
        ui.headerAnim {
            ui.text(DangoMusic.NAME, view.x, view.y + 6f - pos, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            subtitle.forEachIndexed { line, text ->
                ui.text(text, view.x, view.y + 38f + line * 18f - pos, 12f, Theme.TEXT_SECONDARY)
            }
        }
        var y = view.y + headerHeight - pos
        credits.forEachIndexed { index, credit ->
            val top = y
            val height = heights[index]
            if (top + height >= view.y && top <= view.bottom) ui.itemAnim(index) {
                ui.text(I18n.text(credit.title), view.x, top, 15f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                ui.text(credit.author, view.x, top + 23f, 11f, Theme.TEXT_SECONDARY)
                descriptions[index].forEachIndexed { line, text ->
                    ui.text(text, view.x, top + 45f + line * 18f, 12f, Theme.TEXT_SECONDARY)
                }
                if (credit.url.isNotEmpty()) {
                    val linkY = top + 43f + descriptions[index].size * 18f
                    ui.text(ui.trim(credit.url, width - 42f, 11f), view.x, linkY + 8f, 11f, Theme.TEXT_PRIMARY)
                    val copy = LayoutBounds(view.x + width - 30f, linkY, 28f, 28f)
                    if (ui.hover(copy)) ui.card(copy, Theme.CARD_HOVER, 6f)
                    val copied = copiedUrl == credit.url && System.currentTimeMillis() < copiedUntil
                    ui.icon(if (copied) "\u2713" else "\u29c9", copy.x + 14f, copy.y + 14f, 16f, Theme.TEXT_PRIMARY)
                    val hit = copy.intersect(view)
                    ui.tooltip(hit, I18n.text(if (copied) "Project link copied" else "Copy project link"))
                    ui.hits += hit to {
                        DangoClient.platform.clipboard = credit.url
                        copiedUrl = credit.url
                        copiedUntil = System.currentTimeMillis() + 2000L
                    }
                }
                ui.ctx.drawShape(LayoutBounds(view.x, top + height - 8f, width, 1f), ShapeStyle(Theme.CARD))
            }
            y += height
        }
        ui.endClip()
        if (maxScroll > 0f) {
            val thumbHeight = max(30f, view.height * view.height / contentHeight)
            val thumbY = view.y + (view.height - thumbHeight) * pos / maxScroll
            ui.ctx.drawShape(LayoutBounds(view.right - 4f, thumbY, 4f, thumbHeight), ShapeStyle(Theme.TEXT_TERTIARY, 2f))
        }
    }
}
