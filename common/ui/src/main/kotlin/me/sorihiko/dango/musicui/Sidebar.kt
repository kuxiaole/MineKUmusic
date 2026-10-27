package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.ServerMusic
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/**
 * 左侧玻璃侧栏：搜索框 / 主页 · 排行榜 · 正在播放 · 一起听 / 我的歌单 / 设置 / 账号。
 * 选中项下面有一块"果冻"选中块：上下两条边分开动，前面那条边 220ms、后面那条 380ms，拉伸着滑过去。
 */
internal class Sidebar(private val screen: MusicScreen) {
    private val ui = screen.ui

    // 搜索框
    var searchText = ""
    var searchFocused = false
    var searchRect = LayoutBounds()
        private set

    // 歌单列表：滚动目标（行）和平滑后的当前位置
    var listArea = LayoutBounds()
        private set
    private var scroll = 0
    private var scrollPos = 0f

    // 选中块：本帧算出的目标，下一帧往那里动
    private var selKey = ""
    private var selTarget: LayoutBounds? = null
    private var pillKey = ""
    private var pillTop = 0f
    private var pillBot = 0f
    private var pillAlpha = 0f
    private var pillFromTop = 0f
    private var pillFromBot = 0f
    private var pillAt = 0L
    private var pillDown = false

    fun scrollBy(rows: Int) {
        scroll += rows
    }

    fun draw(side: LayoutBounds) {
        val ctx = ui.ctx
        val page = ScreenState.page
        ctx.drawGlass(side, Theme.GLASS_SIDEBAR)
        drawSelectionPill(side)
        var newKey = ""
        var newTarget: LayoutBounds? = null
        val x = side.x + 12f
        val iw = side.width - 24f
        var y = side.y + 12f

        // 搜索框：放大镜 + 输入
        searchRect = LayoutBounds(x, y, iw, 32f)
        ui.card(searchRect, if (searchFocused) Theme.CARD_HOVER else Theme.CARD, 10f)
        if (searchFocused) ctx.drawShape(searchRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1f, RED))
        ctx.drawShape(LayoutBounds(x + 11f, y + 10f, 9f, 9f), ShapeStyle(Color(0f, 0f, 0f, 0f), 4.5f, 1.4f, Theme.TEXT_SECONDARY))
        ctx.drawShape(LayoutBounds(x + 18.5f, y + 18f, 4.5f, 1.6f), ShapeStyle(Theme.TEXT_SECONDARY, 0.8f))
        val caret = if (searchFocused && caretVisible()) "|" else ""
        val shown = if (searchText.isEmpty() && !searchFocused) I18n.text("Search songs...") else searchText + caret
        val shownColor = if (searchText.isEmpty() && !searchFocused) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY
        val maxW = searchRect.right - 10f - (x + 30f)
        var visible = shown
        while (visible.length > 1 && ui.width(visible, 12f) > maxW) visible = visible.substring(1)   // 太长时显示末尾
        ui.text(visible, x + 30f, y + 10f, 12f, shownColor)
        ui.hits += searchRect to { searchFocused = true }
        y += 42f

        fun section(label: String) {
            ui.text(label, x + 2f, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
        }

        fun nav(glyph: String, label: String, active: Boolean, redIcon: Boolean, action: () -> Unit) {
            val row = LayoutBounds(side.x + 6f, y, side.width - 12f, 30f)
            if (active) {
                newKey = "nav:$glyph"
                newTarget = row
            } else if (ui.hover(row)) {
                ui.card(row, Theme.CARD_HOVER, 9f)
            }
            ui.icon(glyph, row.x + 18f, row.y + 15f, 12f, if (active || redIcon) RED else Theme.TEXT_SECONDARY)
            ui.text(label, row.x + 34f, row.y + 10f, 12f, if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            ui.hits += row to action
            y += 32f
        }

        section(DangoMusic.NAME)
        nav("⌂", I18n.text("Home"), page == Page.HOME, true) { screen.go(Page.HOME) }
        section(I18n.text("DISCOVER"))
        nav("▤", I18n.text("Charts"), page == Page.CHARTS, false) { screen.go(Page.CHARTS) }
        nav("♫", I18n.text("Now Playing"), page == Page.QUEUE, false) { screen.go(Page.QUEUE) }
        nav("♬", I18n.text("Server Music") + if (ServerMusic.joined) " · ${ServerMusic.listeners}" else "",
            page == Page.PARTY, ServerMusic.joined) { screen.go(Page.PARTY) }
        y += 4f
        section(I18n.text("MY PLAYLISTS"))

        // 歌单列表（底部留出按钮和账号的位置）
        val rowH = 30f
        val footerH = 92f
        listArea = LayoutBounds(side.x + 6f, y, side.width - 12f, side.bottom - footerH - y)
        val rows = max(1, (listArea.height / rowH).toInt())
        val list = Library.playlists
        scroll = scroll.coerceIn(0, max(0, list.size - rows))
        scrollPos = ui.easeScroll(scrollPos, scroll, rows * 3)
        val firstOwned = list.indexOfFirst { it.owned }
        val first = kotlin.math.floor(scrollPos).toInt()
        val frac = scrollPos - first
        ui.beginClip(listArea)
        for (r in 0..rows) {
            val i = first + r
            if (i < 0) continue
            if (i >= list.size) break
            val p = list[i]
            val row = LayoutBounds(listArea.x, listArea.y + (r - frac) * rowH, listArea.width, rowH - 2f)
            val active = page == Page.PLAYLIST && ScreenState.opened?.id == p.id
            if (active) {
                newKey = "pl:${p.id}"
                newTarget = row
            } else if (ui.hover(row)) {
                ui.card(row, Theme.CARD_HOVER, 9f)
            }
            val liked = i == firstOwned   // 第一个自己的歌单就是"我喜欢的音乐"
            ui.icon(if (liked) "♥" else "≡", row.x + 18f, row.y + 14f, 12f, if (liked || active) RED else Theme.TEXT_SECONDARY)
            val c = if (active) Theme.TEXT_PRIMARY else if (p.owned) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
            ui.text(ui.trim(p.name, row.right - 8f - (row.x + 34f), 12f), row.x + 34f, row.y + 9f, 12f, c)
            ui.hits += row.intersect(listArea) to { screen.openPlaylist(p, null) }
        }
        ui.endClip()
        if (list.isEmpty()) {
            val msg = if (Library.playlistsLoading) I18n.text("Loading...") else if (NeteaseApi.loggedIn) I18n.text("No playlists") else I18n.text("Log in to see your playlists")
            ui.text(msg, x + 2f, listArea.y + 6f, 11f, Theme.TEXT_TERTIARY)
        }

        // 设置按钮（歌单会在打开播放器时自动刷新，不需要刷新按钮）
        val by = side.bottom - footerH + 8f
        val sb = LayoutBounds(x, by, iw, 32f)
        val on = page == Page.SETTINGS
        ui.card(sb, if (on) RED.copy(a = 0.22f) else if (ui.hover(sb)) Theme.CARD_HOVER else Theme.CARD, 10f)
        if (on) ctx.drawShape(sb, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1f, RED.copy(a = 0.6f)))
        val sCol = if (on) RED else Theme.TEXT_PRIMARY
        val sLabel = I18n.text("Settings")
        val sw = 14f + 6f + ui.width(sLabel, 12f, Theme.FONT_SEMIBOLD)
        val sx = sb.x + (sb.width - sw) / 2f
        ui.icon("⚙", sx + 7f, sb.y + 16f, 14f, sCol)
        ui.text(sLabel, sx + 20f, sb.y + 16f - 12f * 0.39f - 0.5f, 12f, sCol, Theme.FONT_SEMIBOLD)
        ui.hits += sb to { screen.toggleSettings() }

        drawAccount(side)

        // 下一帧选中块往这里动
        selKey = newKey
        selTarget = newTarget
    }

    private fun drawAccount(side: LayoutBounds) {
        val ub = LayoutBounds(side.x + 6f, side.bottom - 46f, side.width - 12f, 40f)
        val av = LayoutBounds(ub.x + 6f, ub.y + 6f, 28f, 28f)
        if (NeteaseApi.loggedIn) {
            val aurl = NeteaseApi.avatarUrl
            ui.drawCover(av, if (aurl.isNotEmpty()) CoverCache.get("u:${NeteaseApi.userId}", aurl) else null, 14f)
            val who = NeteaseApi.nickname.ifEmpty { I18n.text("Logged in") }
            val outW = ui.width(I18n.text("Log out"), 10f, Theme.FONT_MEDIUM)
            ui.text(ui.trim(who, ub.right - outW - 18f - (av.right + 10f), 12f), av.right + 10f, ub.y + 14f, 12f, Theme.TEXT_PRIMARY)
            val outB = LayoutBounds(ub.right - outW - 14f, ub.y + 10f, outW + 10f, 20f)
            if (ui.hover(outB)) ui.card(outB, Theme.CARD_HOVER, 6f)
            ui.text(I18n.text("Log out"), outB.x + 5f, outB.y + 6f, 10f, Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
            ui.hits += outB to {
                NeteaseApi.logout()
                Library.refreshAll()
            }
        } else {
            if (ui.hover(ub)) ui.card(ub, Theme.CARD_HOVER, 10f)
            ui.ctx.drawShape(av, ShapeStyle(Theme.TRACK, 14f))
            ui.icon("?", av.x + 14f, av.y + 14f, 12f, Theme.TEXT_SECONDARY)
            ui.text(I18n.text("Log in to NetEase"), av.right + 10f, ub.y + 14f, 12f, RED, Theme.FONT_SEMIBOLD)
            ui.hits += ub to { screen.login.open() }
        }
    }

    /** 侧栏选中块（用上一帧记下的选中行位置） */
    private fun drawSelectionPill(side: LayoutBounds) {
        val now = System.currentTimeMillis()
        val tgt = selTarget
        if (tgt != null) {
            if (pillAlpha < 0.02f || pillKey.isEmpty()) {
                pillTop = tgt.y
                pillBot = tgt.bottom
                pillKey = selKey
                pillAt = 0L
            } else if (selKey != pillKey) {
                pillFromTop = pillTop
                pillFromBot = pillBot
                pillDown = tgt.y > pillTop
                pillAt = now
                pillKey = selKey
            }
            if (pillAt != 0L) {
                val e = (now - pillAt).toFloat()
                val pt = e / (if (pillDown) 380f else 220f)
                val pb = e / (if (pillDown) 220f else 380f)
                pillTop = pillFromTop + (tgt.y - pillFromTop) * ui.easeOut(pt)
                pillBot = pillFromBot + (tgt.bottom - pillFromBot) * ui.easeOut(pb)
                if (pt >= 1f && pb >= 1f) pillAt = 0L
            } else {
                pillTop = tgt.y
                pillBot = tgt.bottom
            }
            pillAlpha = min(1f, pillAlpha + ui.frameDt / 120f)
        } else {
            pillAlpha = max(0f, pillAlpha - ui.frameDt / 120f)
            if (pillAlpha <= 0f) pillKey = ""
        }
        if (pillAlpha > 0.01f && pillBot - pillTop > 2f) {
            ui.ctx.pushOpacity(pillAlpha)
            ui.ctx.drawGlass(LayoutBounds(side.x + 6f, pillTop, side.width - 12f, pillBot - pillTop), Theme.GLASS_SELECTION)
            ui.ctx.popOpacity()
        }
    }

    /** 搜索框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!searchFocused) return false
        when {
            keyCode == Keys.ESCAPE -> searchFocused = false
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> screen.doSearch()
            keyCode == Keys.BACK -> if (searchText.isNotEmpty()) searchText = searchText.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V -> {
                // 游戏里打不了中文时，可以复制后粘贴进来
                val clip = DangoClient.platform.clipboard.replace(Regex("[\\r\\n]"), " ")
                searchText = (searchText + clip).take(80)
            }
            DangoClient.platform.ctrlDown && keyCode == Keys.A -> searchText = ""
            Keys.isAllowedChar(typedChar) -> searchText = (searchText + typedChar).take(80)
        }
        return true
    }
}
