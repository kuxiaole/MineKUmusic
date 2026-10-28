package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.ServerMusic
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.Theme
import kotlin.math.abs
import kotlin.math.max

internal class PartyPage(private val screen: MusicScreen) {
    private val ui = screen.ui
    private var query = ""
    var codeFocused = false
    var codeRect = LayoutBounds(); private set
    var area = LayoutBounds(); private set
    private var searchTab = false
    private var tabBlend = 0f
    private val tabHover = FloatArray(2)
    private val sourcePosition = Spring(320f, 26f)
    private val scrollOffsets = IntArray(2)
    private var scrollOffset: Int
        get() = scrollOffsets[if (searchTab) 1 else 0]
        set(value) { scrollOffsets[if (searchTab) 1 else 0] = value }

    fun draw(bounds: LayoutBounds) {
        val channel = ServerMusic
        ui.text(I18n.text("Server Music"), bounds.x, bounds.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        ui.text(ui.trim(if (channel.available) channel.channelName else I18n.message(channel.status), bounds.width - 160f, 11f),
            bounds.x, bounds.y + 32f, 11f, Theme.TEXT_SECONDARY)
        if (channel.available) {
            ui.pill(LayoutBounds(bounds.right - 138f, bounds.y + 4f, 138f, 32f), if (channel.joined) Theme.CARD else RED,
                I18n.text(if (channel.joined) "Leave channel" else "Join channel")) {
                if (channel.joined) channel.leave() else channel.join()
            }
        } else {
            area = LayoutBounds(); codeRect = LayoutBounds(); codeFocused = false
            return
        }
        val nowY = bounds.y + 62f
        val entry = channel.current
        val cover = LayoutBounds(bounds.x, nowY, 64f, 64f)
        ui.drawCover(cover, entry?.let { CoverCache.track(channel.toTrack(it.track)) }, 8f)
        ui.text(ui.trim(entry?.track?.name ?: I18n.text("Waiting for song requests"), bounds.width - 260f, 16f, Theme.FONT_SEMIBOLD),
            cover.right + 14f, nowY + 4f, 16f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(ui.trim(entry?.track?.artists?.joinToString(" / ") ?: "", bounds.width - 260f, 11f), cover.right + 14f, nowY + 29f, 11f, Theme.TEXT_SECONDARY)
        val detail = I18n.format("%d listeners", channel.listeners) + if (entry != null) " · " + I18n.format("Requested by %s", entry.requesterName) else ""
        ui.text(ui.trim(detail, bounds.width - 260f, 10f), cover.right + 14f, nowY + 49f, 10f, Theme.TEXT_TERTIARY)
        if (entry != null) {
            ui.text(fmtTime(channel.expectedPosition()) + " / " + fmtTime(entry.track.durationMs), bounds.right - 140f, nowY + 3f, 11f, Theme.TEXT_SECONDARY)
            if (channel.joined && channel.canVote) ui.pill(LayoutBounds(bounds.right - 156f, nowY + 27f, 156f, 30f), Theme.CARD,
                I18n.format("Skip vote %d/%d", channel.votes, channel.votesRequired), "▶") { channel.vote() }
        }
        val toolbarY = nowY + 86f
        val tabs = LayoutBounds(bounds.x, toolbarY, 230f, 30f)
        ui.card(tabs, Theme.CARD, 8f)
        val target = if (searchTab) 1f else 0f
        tabBlend = ui.approach(tabBlend, target, 70f)
        if (abs(tabBlend - target) < 0.002f) tabBlend = target
        val indicator = if (screen.reducedChannelMotion) target else tabBlend
        ui.ctx.drawGlass(LayoutBounds(tabs.x + indicator * 115f, toolbarY, 115f, 30f), Theme.GLASS_SELECTION)
        for (index in 0..1) {
            val tab = LayoutBounds(tabs.x + index * 115f, toolbarY, 115f, 30f)
            val selected = searchTab == (index == 1)
            tabHover[index] = ui.approach(tabHover[index], if (!selected && ui.hover(tab)) 1f else 0f, 70f)
            ui.card(tab, Theme.CARD_HOVER.copy(a = Theme.CARD_HOVER.a * tabHover[index]), 8f)
            val label = I18n.text(if (index == 0) "Channel queue" else "Request a song")
            ui.text(label, tab.x + (tab.width - ui.width(label, 11f)) / 2, tab.y + 10f, 11f,
                if (selected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
            ui.hits += tab to {
                if (searchTab != (index == 1)) {
                    searchTab = index == 1
                    scrollOffset = 0
                }
                codeFocused = searchTab
                screen.sidebar.searchFocused = false
                screen.tracks.filterFocused = false
            }
        }
        if (!searchTab) { codeRect = LayoutBounds(); codeFocused = false }
        for (index in 0..1) {
            val alpha = if (index == 1) tabBlend else 1f - tabBlend
            if (alpha <= 0f) continue
            val firstHit = ui.hits.size
            ui.shifted(0f, 0f, alpha) { drawPanel(bounds, tabs, index == 1) }
            // Both panels remain visible during the fade; only the selected one accepts clicks.
            if (searchTab != (index == 1)) ui.hits.subList(firstHit, ui.hits.size).clear()
        }
    }

    private fun drawPanel(bounds: LayoutBounds, tabs: LayoutBounds, search: Boolean) {
        val channel = ServerMusic
        val active = search == searchTab
        val toolbarY = tabs.y
        var statusY = toolbarY + 42f
        if (search) {
            val sourceY = toolbarY + 42f
            val sourceWidth = 88f
            val sourceBar = LayoutBounds(bounds.x, sourceY, sourceWidth * channel.sources.size, 30f)
            ui.card(sourceBar, Theme.CARD, 8f)
            val selected = channel.sources.indexOf(channel.searchSource).coerceAtLeast(0).toFloat()
            val position = sourcePosition.tick(selected)
            ui.ctx.drawGlass(LayoutBounds(sourceBar.x + sourceWidth * (if (screen.reducedChannelMotion) selected else position), sourceY, sourceWidth, 30f), Theme.GLASS_SELECTION)
            channel.sources.forEachIndexed { index, source ->
                val button = LayoutBounds(sourceBar.x + index * sourceWidth, sourceY, sourceWidth, 30f)
                val label = I18n.text(source.label)
                ui.text(label, button.x + (button.width - ui.width(label, 11f)) / 2f, button.y + 10f, 11f, Theme.TEXT_PRIMARY)
                ui.hits += button to { channel.selectSearchSource(source); scrollOffset = 0 }
            }
            val compact = bounds.width < 460f
            val inputY = sourceY + if (compact) 40f else 0f
            val inputX = if (compact) bounds.x else sourceBar.right + 12f
            val searchRect = LayoutBounds(inputX, inputY, max(40f, bounds.right - inputX - 82f), 30f)
            if (active) codeRect = searchRect
            ui.card(searchRect, if (codeFocused) Theme.CARD_HOVER else Theme.CARD, 8f)
            val text = if (query.isEmpty() && !codeFocused) I18n.text("Search songs...") else query + if (codeFocused && caretVisible()) "|" else ""
            ui.text(ui.trim(text, searchRect.width - 20f, 11f), searchRect.x + 10f, searchRect.y + 10f, 11f, Theme.TEXT_PRIMARY)
            ui.hits += searchRect to { codeFocused = true; screen.sidebar.searchFocused = false; screen.tracks.filterFocused = false }
            ui.pill(LayoutBounds(searchRect.right + 10f, inputY, 72f, 30f), RED, I18n.text("Search")) { submitSearch() }
            statusY = inputY + 42f
        }
        val status = if (search && channel.searching) I18n.text("Searching...") else I18n.message(channel.status)
        ui.text(ui.trim(status, bounds.width, 11f), bounds.x, statusY, 11f, Theme.TEXT_SECONDARY)
        val area = LayoutBounds(bounds.x, statusY + 24f, bounds.width, max(0f, bounds.bottom - statusY - 68f))
        if (active) this.area = area
        val visibleRows = max(1, (area.height / 46f).toInt())
        val count = if (search) channel.searchResults.size else channel.queue.size
        val tabIndex = if (search) 1 else 0
        val scrollOffset = scrollOffsets[tabIndex].coerceIn(0, max(0, count - visibleRows))
        scrollOffsets[tabIndex] = scrollOffset
        ui.beginClip(area)
        if (count == 0) ui.text(I18n.text(if (search) "No results" else "Nothing in the queue yet"), area.x + 10f, area.y + 20f, 12f, Theme.TEXT_TERTIARY)
        for (index in scrollOffset until (scrollOffset + visibleRows).coerceAtMost(count)) {
            val track = if (search) channel.searchResults[index] else channel.queue[index].track
            val row = LayoutBounds(area.x, area.y + (index - scrollOffset) * 46f, area.width, 42f)
            if (active && ui.hover(row)) ui.card(row, Theme.CARD_HOVER, 8f)
            val image = LayoutBounds(row.x + 6f, row.y + 4f, 34f, 34f)
            ui.drawCover(image, CoverCache.track(channel.toTrack(track)), 6f)
            val sourceName = I18n.text(if (track.ref().source == "qq") "QQ Music" else "NetEase")
            val suffix = sourceName + " · " + if (search) track.artists.joinToString(" / ") else channel.queue[index].requesterName
            ui.text(ui.trim(track.name, row.width - 180f, 12f), image.right + 12f, row.y + 6f, 12f, Theme.TEXT_PRIMARY)
            ui.text(ui.trim(suffix, row.width - 180f, 10f), image.right + 12f, row.y + 25f, 10f, Theme.TEXT_SECONDARY)
            ui.text(fmtTime(track.durationMs), row.right - 100f, row.y + 14f, 11f, Theme.TEXT_TERTIARY)
            val action = LayoutBounds(row.right - 40f, row.y + 6f, 30f, 30f)
            val canAct = channel.joined && if (search) channel.canRequest else channel.queue[index].requesterId == channel.playerId
            if (canAct) {
                val selectedEntry = if (search) null else channel.queue[index].entryId
                ui.icon(if (search) "+" else "×", action.x + 15f, action.y + 15f, 18f, RED)
                if (active) ui.tooltip(action, I18n.text(if (search) "Add to server queue" else "Withdraw song"))
                ui.hits += action to { if (selectedEntry == null) channel.requestSong(track) else channel.withdraw(selectedEntry) }
            }
        }
        ui.endClip()
        val page = if (search) channel.searchPage else channel.queuePage
        val hasNext = if (search) channel.searchHasNext else channel.queueHasNext
        val footerY = bounds.bottom - 28f
        val label = I18n.format("Page %d", page + 1) + if (!search) " · " + I18n.format("%d songs", channel.queueTotal) else ""
        ui.text(label, bounds.x, footerY + 8f, 11f, Theme.TEXT_SECONDARY)
        fun pageButton(x: Float, glyph: String, target: Int, enabled: Boolean) {
            val b = LayoutBounds(x, footerY, 30f, 28f)
            ui.icon(glyph, b.x + 15f, b.y + 14f, 13f, if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY)
            if (enabled) ui.hits += b to { scrollOffsets[tabIndex] = 0; if (search) channel.searchPage(target) else channel.requestQueue(target) }
        }
        pageButton(bounds.right - 76f, "◀", page - 1, page > 0)
        pageButton(bounds.right - 32f, "▶", page + 1, hasNext)
    }
    fun scroll(wheel: Int) { scrollOffset += if (wheel > 0) -3 else 3 }
    private fun submitSearch() { codeFocused = false; scrollOffset = 0; ServerMusic.search(query) }
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!codeFocused) return false
        when {
            keyCode == Keys.ESCAPE -> codeFocused = false
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> submitSearch()
            keyCode == Keys.BACK -> if (query.isNotEmpty()) query = query.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V -> query = (query + DangoClient.platform.clipboard.replace('\n', ' ').replace('\r', ' ')).take(120)
            DangoClient.platform.ctrlDown && keyCode == Keys.A -> query = ""
            Keys.isAllowedChar(typedChar) -> query = (query + typedChar).take(120)
        }
        return true
    }
}
