package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.ServerMusic
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/** 底部播放栏：左边当前歌曲，中间控制按钮 + 进度条，右边单曲循环 / 随机 + 音量 */
internal class PlayerBar(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private var progRect = LayoutBounds()
    private var volRect = LayoutBounds()

    fun draw(bar: LayoutBounds) {
        val ctx = ui.ctx
        ui.card(bar, Theme.CARD, 16f)
        val t = MusicPlayer.current

        // 左：封面 + 歌名 / 歌手
        val cs = 48f
        val coverBox = LayoutBounds(bar.x + 13f, bar.y + (bar.height - cs) / 2f, cs, cs)
        ui.drawCover(coverBox, t?.let { CoverCache.track(it) }, 8f)
        val infoX = coverBox.right + 12f
        val infoW = 220f
        val title = t?.name ?: I18n.text(if (ServerMusic.joined) "Waiting for song requests" else "Not playing")
        ui.text(ui.trim(title, infoW, 14f, Theme.FONT_SEMIBOLD), infoX, coverBox.y + 9f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val sub = when {
            t == null -> "-"
            MusicPlayer.message.isNotEmpty() -> I18n.message(MusicPlayer.message)
            else -> t.artists.joinToString(" / ")
        }
        ui.text(ui.trim(sub, infoW, 11f), infoX, coverBox.y + 28f, 11f, Theme.TEXT_SECONDARY)

        // 中：上一首 / 播放 / 下一首 / 队列
        val cx = bar.x + bar.width / 2f
        val cy = bar.y + 25f
        fun btn(b: LayoutBounds) {
            if (ui.hover(b)) ctx.drawShape(b, ShapeStyle(Theme.CARD_HOVER, b.height / 2f))
        }
        val playB = LayoutBounds(cx - 17f, cy - 17f, 34f, 34f)
        val prevB = LayoutBounds(cx - 58f - 14f, cy - 14f, 28f, 28f)
        val nextB = LayoutBounds(cx + 58f - 14f, cy - 14f, 28f, 28f)
        val queueB = LayoutBounds(cx + 112f - 14f, cy - 14f, 28f, 28f)
        btn(prevB); btn(playB); btn(nextB); btn(queueB)
        if (MusicPlayer.state == MusicPlayer.State.PLAYING) {
            ui.card(LayoutBounds(cx - 6f, cy - 8f, 4f, 16f), Theme.TEXT_PRIMARY, 1.5f)
            ui.card(LayoutBounds(cx + 2f, cy - 8f, 4f, 16f), Theme.TEXT_PRIMARY, 1.5f)
        } else {
            ui.icon("▶", cx + 1.5f, cy, 18f, Theme.TEXT_PRIMARY)
        }
        // ⏮ = 竖条 + ◀，⏭ = ▶ + 竖条
        val personalControl = if (ServerMusic.joined) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY
        ui.card(LayoutBounds(prevB.x + 8f, cy - 5.5f, 2.2f, 11f), personalControl, 1f)
        ui.icon("◀", prevB.x + 16.5f, cy, 12f, personalControl)
        ui.icon("▶", nextB.x + 11.5f, cy, 12f, Theme.TEXT_PRIMARY)
        ui.card(LayoutBounds(nextB.right - 10.2f, cy - 5.5f, 2.2f, 11f), Theme.TEXT_PRIMARY, 1f)
        val onQueue = ScreenState.page == (if (ServerMusic.joined) Page.PARTY else Page.QUEUE)
        ui.icon("≡", queueB.x + 14f, cy, 14f, if (onQueue) RED else Theme.TEXT_PRIMARY)
        if (!ServerMusic.joined) ui.hits += prevB to { ServerMusic.previous() }
        ui.hits += playB to { ServerMusic.togglePause() }
        ui.hits += nextB to { ServerMusic.next() }
        ui.tooltip(playB, I18n.text(if (ServerMusic.joined) "Pause your listening" else "Play / Pause"))
        ui.tooltip(nextB, I18n.text(if (ServerMusic.joined) "Vote to skip" else "Next"))
        ui.hits += queueB to { if (ServerMusic.joined) screen.go(Page.PARTY) else if (ScreenState.page == Page.QUEUE) screen.goBackOrHome() else screen.go(Page.QUEUE) }

        // 进度条：时间 | 条 | 时间
        val dur = MusicPlayer.durationMs
        val pos = MusicPlayer.positionMs
        val pw = min(440f, bar.width * 0.4f)
        progRect = LayoutBounds(cx - pw / 2f, bar.y + 54f, pw, 4f)
        val l = fmtTime(pos)
        val rgt = fmtTime(dur)
        ui.text(l, progRect.x - 10f - ui.width(l, 10f), progRect.y - 3f, 10f, Theme.TEXT_SECONDARY)
        ui.text(rgt, progRect.right + 10f, progRect.y - 3f, 10f, Theme.TEXT_SECONDARY)
        ui.card(progRect, Theme.TRACK, 2f)
        if (dur > 0) {
            val f = (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ui.card(LayoutBounds(progRect.x, progRect.y, max(4f, progRect.width * f), progRect.height), RED, 2f)
            if (progRect.expand(0f, 6f).contains(ui.mx, ui.my)) {
                val kx = progRect.x + progRect.width * f
                ctx.drawShape(LayoutBounds(kx - 5f, progRect.y - 3f, 10f, 10f), ShapeStyle(Theme.TEXT_PRIMARY, 5f))
            }
        }

        // 右：单曲循环 / 随机 + 音量
        val vy = bar.y + bar.height / 2f
        volRect = LayoutBounds(bar.right - 30f - 110f, vy - 2f, 110f, 4f)
        speaker(volRect.x - 22f, vy, false)
        speaker(volRect.right + 9f, vy, true)
        ui.card(volRect, Theme.TRACK, 2f)
        ui.card(LayoutBounds(volRect.x, volRect.y, max(4f, volRect.width * MusicPlayer.volume), volRect.height), RED, 2f)
        val knobX = volRect.x + volRect.width * MusicPlayer.volume
        ctx.drawShape(LayoutBounds(knobX - 5f, volRect.y - 3f, 10f, 10f), ShapeStyle(Theme.TEXT_PRIMARY, 5f))

        val shB = LayoutBounds(volRect.x - 72f, vy - 14f, 32f, 28f)
        ui.card(shB, if (!ServerMusic.joined && MusicPlayer.shuffle) RED.copy(a = 0.85f) else if (ui.hover(shB)) Theme.CARD_HOVER else Color(1f, 1f, 1f, 0.07f), 9f)
        ui.icon("⇄", shB.x + 16f, vy, 14f, personalControl)
        if (!ServerMusic.joined) ui.hits += shB to { module.toggleShuffle() }

        val repeatB = LayoutBounds(shB.x - 40f, vy - 14f, 32f, 28f)
        ui.card(repeatB, if (!ServerMusic.joined && MusicPlayer.repeatOne) RED.copy(a = 0.85f) else if (ui.hover(repeatB)) Theme.CARD_HOVER else Color(1f, 1f, 1f, 0.07f), 9f)
        ui.icon("↻", repeatB.x + 16f, vy, 14f, personalControl)
        if (!ServerMusic.joined) ui.hits += repeatB to { module.toggleRepeatOne() }
    }

    /** 小喇叭图标：方块 + 喇叭口，loud = 右边再加两道声波 */
    private fun speaker(x: Float, cy: Float, loud: Boolean) {
        ui.card(LayoutBounds(x, cy - 2.5f, 3.5f, 5f), Theme.TEXT_SECONDARY, 1f)
        ui.card(LayoutBounds(x + 3f, cy - 5f, 3.5f, 10f), Theme.TEXT_SECONDARY, 1.2f)
        if (loud) {
            ui.card(LayoutBounds(x + 8.5f, cy - 2.5f, 1.4f, 5f), Theme.TEXT_SECONDARY, 0.7f)
            ui.card(LayoutBounds(x + 11.5f, cy - 4.5f, 1.4f, 9f), Theme.TEXT_SECONDARY, 0.7f)
        }
    }

    /** 点在进度条 / 音量条上就处理掉，返回 true */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (progRect.expand(0f, 6f).contains(mx, my)) {
            val dur = MusicPlayer.durationMs
            if (dur > 0) ServerMusic.seek(((mx - progRect.x) / progRect.width * dur).toLong())
            return true
        }
        if (volRect.expand(6f, 8f).contains(mx, my)) {
            setVolumeAt(mx)
            return true
        }
        return false
    }

    /** 按住拖动音量 */
    fun mouseDrag(mx: Int, my: Int) {
        if (volRect.expand(12f, 12f).contains(mx, my)) setVolumeAt(mx)
    }

    private fun setVolumeAt(x: Int) {
        val v = ((x - volRect.x) / volRect.width).coerceIn(0f, 1f)
        module.setVolume(v)
        MusicPlayer.volume = v
    }
}
