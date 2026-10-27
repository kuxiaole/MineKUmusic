package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 设置页（侧栏齿轮）：Lyrics / Music & HUD / About。
 * 直接读写各模块的 @ModuleValue，改了立刻生效，ConfigManager 会自动存盘。
 */
internal class SettingsPage(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui
    private val about = AboutPage(screen)

    private enum class Ctl { TOGGLE, SLIDER, SWATCH, KEY, LANGUAGE }

    /** scale：显示值 = 实际值 × scale（百分比的选项是 100），手动输入时按显示值换算回去 */
    private class Row(
        val label: String, val desc: String, val value: Any, val ctl: Ctl,
        val dep: BooleanValue?, val fmt: (Any) -> String, val scale: Float = 1f,
    )

    /** owner：所属模块（模块关掉时整组变暗）；toggle：组标题右边显示模块总开关 */
    private class Group(val title: String, val sub: String, val owner: Module, val toggle: Boolean, val rows: List<Row>)

    /** 显示成百分比的选项 */
    private val percentKeys = setOf("volume", "glowRadius", "posX", "posY")

    private val swatchColors = listOf(
        Color(1f, 1f, 1f, 1f),            // 跟随字色
        Color(1f, 0.38f, 0.62f, 1f),      // 粉
        Color(0.32f, 0.66f, 1f, 1f),      // 蓝
        Color(1f, 0.74f, 0.30f, 1f),      // 金
    )

    private val tabs: List<List<Group>> by lazy { buildSettings() }

    /** 本帧画了设置列表的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()
    // 平滑滚动（逻辑像素）
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f

    // 滑条拖动
    private var dragValue: Any? = null
    private var dragTrack = LayoutBounds()

    // 点数值手动输入
    private var editRow: Row? = null
    private var editText = ""
    private var editRect = LayoutBounds()

    // 正在录快捷键的那一项，以及本帧所有快捷键按钮的区域（点别处就取消录制）
    var bindingKey: IntValue? = null
    private val keyButtons = mutableListOf<LayoutBounds>()
    private val keyResetButtons = mutableListOf<Hit>()

    val editing: Boolean get() = editRow != null

    // ---------------------------------------------------------------- 每帧

    fun tick() {
        about.tick()
        pos = ui.approach(pos, target, 70f)
        // 滑条：按住拖动
        if (dragValue != null) {
            if (DangoClient.platform.isMouseDown(0)) applyDrag() else dragValue = null
        }
    }

    fun beginFrame() {
        keyButtons.clear()
        keyResetButtons.clear()
    }

    fun resetScroll() {
        about.resetScroll()
        target = 0f
        pos = 0f
    }

    fun scroll(wheel: Int) {
        if (ScreenState.settingsTab == 2) { about.scroll(wheel); return }
        target = (target + (if (wheel > 0) -60f else 60f) * (if (DangoClient.platform.ctrlDown) 4f else 1f)).coerceIn(0f, maxScroll)
    }

    fun stopDrag() {
        dragValue = null
    }

    // ---------------------------------------------------------------- 数值输入

    private fun numberText(r: Row): String = when (val v = r.value) {
        is IntValue -> (v.value * r.scale).roundToInt().toString()
        is FloatValue -> {
            val x = v.value * r.scale
            if (r.scale != 1f || v.decimals == 0) x.roundToInt().toString() else "%.${v.decimals}f".format(x)
        }
        else -> ""
    }

    private fun startEdit(r: Row) {
        if (editRow === r) return
        commitEdit()
        editRow = r
        editText = numberText(r)
        screen.blurTextFields()
        bindingKey = null
    }

    /** 输入的数字写回去（超出范围的自动夹到范围内，乱输的忽略） */
    fun commitEdit() {
        val r = editRow ?: return
        editRow = null
        val n = editText.trim().replace(',', '.').toFloatOrNull() ?: return
        when (val v = r.value) {
            is IntValue -> v.setValue((n / r.scale).roundToInt())
            is FloatValue -> v.setValue(n / r.scale)
        }
    }

    /** 点到别处时：收起快捷键录制和数值输入 */
    fun onClickElsewhere(hit: Hit?) {
        if (bindingKey != null && (hit == null || hit.first !in keyButtons)) bindingKey = null
        if (editRow != null && (hit == null || hit.first != editRect.intersect(area))) commitEdit()
    }

    // ---------------------------------------------------------------- 选项列表

    private fun fieldsOf(m: Module): LinkedHashMap<String, Pair<ModuleValue, Any>> {
        val out = LinkedHashMap<String, Pair<ModuleValue, Any>>()
        for (f in m.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            f.isAccessible = true
            val v = f.get(m) ?: continue
            out[f.name] = ann to v
        }
        return out
    }

    private fun fmtValue(v: Any): String = when (v) {
        is IntValue -> v.value.toString()
        is FloatValue -> "%.${v.decimals}f".format(v.value)
        else -> ""
    }

    private fun percent(v: Any): String = "${((v as FloatValue).value * 100f).roundToInt()}%"

    private fun buildSettings(): List<List<Group>> {
        val mm = DangoMusic.instance.moduleManager

        /** 一个模块的设置；take(...) 按名字取出若干行，剩下的最后放进 "Other" */
        class Src(val m: Module, val deps: Map<String, String> = emptyMap(), val fmts: Map<String, (Any) -> String> = emptyMap()) {
            val all = fieldsOf(m)
            val left = LinkedHashMap(all)
            fun take(names: List<String>? = null): List<Row> = (names ?: left.keys.toList()).mapNotNull { k ->
                val (ann, v) = left.remove(k) ?: return@mapNotNull null
                val ctl = when {
                    k == "language" -> Ctl.LANGUAGE
                    v is BooleanValue -> Ctl.TOGGLE
                    k == "glowColor" -> Ctl.SWATCH
                    k == "openKey" || (k.startsWith("key") && v is IntValue) -> Ctl.KEY
                    v is IntValue || v is FloatValue -> Ctl.SLIDER
                    else -> return@mapNotNull null
                }
                val dep = deps[k]?.let { all[it]?.second as? BooleanValue }
                Row(ann.name.ifEmpty { k }, ann.desc, v, ctl, dep, fmts[k] ?: ::fmtValue, if (k in percentKeys) 100f else 1f)
            }
        }

        val ly = Src(
            mm.get(LyricsModule::class.java),
            deps = mapOf(
                "distance" to "worldMode", "facePlayer" to "worldMode", "keepAway" to "worldMode", "stayOnScreen" to "worldMode", "aboveGround" to "worldMode", "spread" to "worldMode",
                "glowColor" to "glow", "glowStrength" to "glow", "glowRadius" to "glow",
                "wiggleFps" to "wiggle", "wiggleAmount" to "wiggle",
                "flashSize" to "textFlash",
            ),
            fmts = mapOf(
                "spread" to { v: Any -> "${(v as FloatValue).value.roundToInt()}°" },
                "glowRadius" to ::percent,
            ),
        )
        val lyrics = mutableListOf(
            Group("Placement", "In the world", ly.m, false, ly.take(listOf("worldMode", "distance", "facePlayer", "keepAway", "stayOnScreen", "aboveGround", "spread", "scale", "smoothFont", "splitLines"))),
            Group("Glow", "Deep Glow", ly.m, false, ly.take(listOf("glow", "glowColor", "glowStrength", "glowRadius"))),
            Group("Motion", "Animation", ly.m, false, ly.take(listOf("wiggle", "wiggleFps", "wiggleAmount", "textFlash", "wordPop", "flashSize"))),
        )
        // 左上角的原版风格"正在播放"和 Song card 是同一类东西，放到 Music & HUD 里
        val nowPlaying = Group("Now playing", "Classic overlay", ly.m, false, ly.take(listOf("nowPlaying", "showCover")))
        ly.take().let { if (it.isNotEmpty()) lyrics += Group("Other", "More", ly.m, false, it) }

        val player = Src(module, fmts = mapOf("volume" to ::percent))
        player.left.remove("shuffle")   // 随机播放用播放栏上的按钮就够了，设置页不再显示（状态照样保存）
        player.left.remove("repeatOne") // 单曲循环同样通过播放栏控制
        val card = Src(mm.get(SongCardModule::class.java), fmts = mapOf("posX" to ::percent, "posY" to ::percent))
        val island = Src(mm.get(DynamicIslandModule::class.java), fmts = mapOf("notifyTime" to { v: Any -> "%.1f s".format((v as FloatValue).value) }))
        val music = listOf(
            Group("Player", "Playback", player.m, false, player.take(listOf("volume", "language", "reducedChannelMotion"))),
            Group("Hotkeys", "Click, then press a key · Backspace clears", player.m, false, player.take()),
            Group("Song card", "Overlay", card.m, true, card.take()),
            nowPlaying,
            Group("Dynamic Island", "Overlay", island.m, true, island.take()),
        )
        return listOf(lyrics, music)
    }

    // ---------------------------------------------------------------- 绘制

    fun draw(page: LayoutBounds) {
        ScreenState.settingsTab = ScreenState.settingsTab.coerceIn(0, 2)
        val compact = page.width < 480f
        val contentTop = page.y + if (compact) 106f else 70f
        ui.headerAnim {
            ui.text(I18n.text("Settings"), page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            val subtitle = I18n.text(if (ScreenState.settingsTab == 2) "Project origins and credits" else "Changes apply instantly and are saved automatically")
            ui.text(ui.trim(subtitle, page.width, 11f, Theme.FONT_SEMIBOLD), page.x, page.y + if (compact) 80f else 44f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            drawTabs(page, compact)
        }

        val view = LayoutBounds(page.x, contentTop, page.width, max(0f, page.bottom - contentTop))
        area = view
        if (ScreenState.settingsTab == 2) { about.draw(view); return }
        val groups = tabs[ScreenState.settingsTab.coerceIn(0, tabs.size - 1)]

        // 两列瀑布流：每组放进当前较矮的一列
        val gap = 14f
        val rowH = 40f
        val headH = 36f
        val cols = if (view.width >= 560f) 2 else 1
        val colW = (view.width - 12f - gap * (cols - 1)) / cols
        val colY = FloatArray(cols)
        val placed = groups.map { g ->
            val h = headH + g.rows.size * rowH + 6f
            var c = 0
            for (i in 1 until cols) if (colY[i] < colY[c] - 0.5f) c = i
            val p = Triple(c, colY[c], h)
            colY[c] += h + gap
            p
        }
        val contentH = max(0f, (colY.maxOrNull() ?: 0f) - gap)
        maxScroll = max(0f, contentH - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)

        ui.beginClip(view)
        groups.forEachIndexed { gi, g ->
            val (c, gy, gh) = placed[gi]
            val b = LayoutBounds(view.x + c * (colW + gap), view.y + gy - pos, colW, gh)
            if (b.bottom < view.y || b.y > view.bottom) return@forEachIndexed
            ui.itemAnim(gi) { drawGroup(b, g, view, rowH, headH) }
        }
        ui.endClip()

        // 滚动条（只显示位置）
        if (maxScroll > 0f && contentH > 0f) {
            val th = max(30f, view.height * view.height / contentH)
            val ty = view.y + (view.height - th) * (pos / maxScroll)
            ui.ctx.drawShape(LayoutBounds(view.right - 4f, ty, 4f, th), ShapeStyle(Color(1f, 1f, 1f, 0.30f), 2f))
        }
    }

    /** 标签：Lyrics / Music & HUD / About */
    private fun drawTabs(page: LayoutBounds, compact: Boolean) {
        val names = listOf(I18n.text("Lyrics"), I18n.text("Music & HUD"), I18n.text("About"))
        val tabW = if (compact) ((page.width - 8f) / names.size).coerceIn(1f, 104f) else 104f
        val bar = LayoutBounds(if (compact) page.x else page.right - tabW * names.size - 8f,
            page.y + if (compact) 40f else 2f, tabW * names.size + 8f, 32f)
        ui.card(bar, Theme.CARD, 11f)
        names.forEachIndexed { i, n ->
            val b = LayoutBounds(bar.x + 4f + i * tabW, bar.y + 4f, tabW, 24f)
            val on = i == ScreenState.settingsTab
            if (on) ui.ctx.drawShape(b, ShapeStyle(RED, 8f)) else if (ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 8f)
            val label = ui.trim(n, max(1f, b.width - 10f), 12f, Theme.FONT_SEMIBOLD)
            val w = ui.width(label, 12f, Theme.FONT_SEMIBOLD)
            ui.text(label, b.x + (b.width - w) / 2f, b.y + 12f - 12f * 0.39f - 0.5f, 12f,
                if (on) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            if (label != n) ui.tooltip(b, n)
            ui.hits += b to {
                if (ScreenState.settingsTab != i) {
                    commitEdit()
                    stopDrag()
                    ScreenState.settingsTab = i
                    resetScroll()
                    bindingKey = null
                }
            }
        }
    }

    private fun drawGroup(b: LayoutBounds, g: Group, clip: LayoutBounds, rowH: Float, headH: Float) {
        val ctx = ui.ctx
        ctx.drawShape(b, ShapeStyle(Color(1f, 1f, 1f, 0.05f), 14f))
        val x = b.x + 14f
        val iw = b.width - 28f
        val title = I18n.text(g.title)
        ui.text(title, x, b.y + 13f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text(g.sub), x + ui.width(title, 13f, Theme.FONT_SEMIBOLD) + 8f, b.y + 14.5f, 10f, Theme.TEXT_TERTIARY)
        if (g.toggle) {
            val m = g.owner
            ui.toggleSwitch(LayoutBounds(b.right - 14f - 34f, b.y + 9f, 34f, 20f), m.enabled, clip) { m.enabled = !m.enabled }
        }
        var y = b.y + headH
        val moduleOff = !g.owner.enabled
        for (r in g.rows) {
            ctx.drawShape(LayoutBounds(x, y, iw, 1f), ShapeStyle(Color(1f, 1f, 1f, 0.06f)))
            val dim = moduleOff || (r.dep != null && !r.dep.get())
            if (dim) ctx.pushOpacity(0.4f)
            drawRow(LayoutBounds(x, y, iw, rowH), r, clip)
            if (dim) ctx.popOpacity()
            y += rowH
        }
    }

    private fun drawRow(b: LayoutBounds, r: Row, clip: LayoutBounds) {
        val ctlW = when (r.ctl) {
            Ctl.TOGGLE -> 34f
            Ctl.SLIDER -> 166f
            Ctl.SWATCH -> 16f * 4 + 8f * 3
            Ctl.KEY -> 130f
            Ctl.LANGUAGE -> 142f
        }
        val textW = b.width - ctlW - 14f
        ui.text(ui.trim(I18n.text(r.label), textW, 12f, Theme.FONT_MEDIUM), b.x, b.y + 8f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        if (r.desc.isNotEmpty()) ui.text(ui.trim(I18n.text(r.desc), textW, 9.5f), b.x, b.y + 24f, 9.5f, Theme.TEXT_TERTIARY)
        val cy = b.y + b.height / 2f
        val cx = b.right - ctlW
        when (r.ctl) {
            Ctl.TOGGLE -> {
                val v = r.value as BooleanValue
                ui.toggleSwitch(LayoutBounds(cx, cy - 10f, 34f, 20f), v.get(), clip) { v.toggle() }
            }
            Ctl.SLIDER -> drawSlider(b, r, clip, cx, cy)
            Ctl.SWATCH -> {
                val v = r.value as IntValue
                swatchColors.forEachIndexed { i, col ->
                    if (i > v.maxRange) return@forEachIndexed
                    val sb = LayoutBounds(cx + i * 24f, cy - 8f, 16f, 16f)
                    if (v.value == i) ui.ctx.drawShape(sb.expand(3f, 3f), ShapeStyle(Color(0f, 0f, 0f, 0f), 11f, 1.5f, Theme.TEXT_PRIMARY))
                    ui.ctx.drawShape(sb, ShapeStyle(col, 8f))
                    ui.hits += sb.expand(4f, 4f).intersect(clip) to { v.setValue(i) }
                }
            }
            Ctl.KEY -> drawKeyButton(r.value as IntValue, clip, cx, cy)
            Ctl.LANGUAGE -> {
                val v = r.value as IntValue
                listOf("English", "中文").forEachIndexed { i, label ->
                    val button = LayoutBounds(cx + i * 74f, cy - 12f, 68f, 24f)
                    ui.card(button, if (v.value == i) RED else if (ui.hover(button)) Theme.CARD_HOVER else Theme.CARD, 7f)
                    ui.text(label, button.x + (button.width - ui.width(label, 11f, Theme.FONT_MEDIUM)) / 2f,
                        cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                    ui.hits += button.intersect(clip) to {
                        v.setValue(i)
                        module.applyLanguage()
                    }
                }
            }
        }
    }

    private fun drawSlider(b: LayoutBounds, r: Row, clip: LayoutBounds, cx: Float, cy: Float) {
        val ctx = ui.ctx
        val track = LayoutBounds(cx + 2f, cy - 2f, 108f, 4f)
        val p = when (val v = r.value) {
            is IntValue -> v.normalize()
            is FloatValue -> v.normalize()
            else -> 0f
        }.coerceIn(0f, 1f)
        val active = dragValue === r.value
        if (active) dragTrack = track
        ctx.drawShape(track, ShapeStyle(Theme.TRACK, 2f))
        ctx.drawShape(LayoutBounds(track.x, track.y, track.width * p, track.height), ShapeStyle(RED, 2f))
        val k = if (active || ui.hover(track.expand(6f, 10f))) 14f else 11f
        ctx.drawShape(LayoutBounds(track.x + track.width * p - k / 2f, cy - k / 2f, k, k), ShapeStyle(Color(1f, 1f, 1f, 1f), k / 2f))
        // 数值：点一下可以直接输入
        val vb = LayoutBounds(b.right - 46f, cy - 10f, 46f, 20f)
        if (editRow === r) {
            editRect = vb
            ui.card(vb, Theme.CARD_HOVER, 6f)
            ctx.drawShape(vb, ShapeStyle(Color(0f, 0f, 0f, 0f), 6f, 1f, RED))
            val caret = if (caretVisible()) "|" else ""
            val et = editText + caret
            ui.text(et, vb.right - 6f - ui.width(et, 11f, Theme.FONT_SEMIBOLD), cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        } else {
            val s = r.fmt(r.value)
            val sw = ui.width(s, 11f, Theme.FONT_SEMIBOLD)
            val hot = ui.hover(vb)
            if (hot) ui.card(LayoutBounds(b.right - sw - 6f, cy - 10f, sw + 6f, 20f), Theme.CARD_HOVER, 6f)
            ui.text(s, b.right - sw - (if (hot) 3f else 0f), cy - 11f * 0.39f - 0.5f, 11f,
                if (active) RED else if (hot) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        ui.hits += vb.intersect(clip) to { startEdit(r) }
        ui.hits += track.expand(8f, 12f).intersect(clip) to {
            dragValue = r.value
            dragTrack = track
            applyDrag()
        }
    }

    private fun drawKeyButton(v: IntValue, clip: LayoutBounds, cx: Float, cy: Float) {
        val kb = LayoutBounds(cx, cy - 12f, 72f, 24f)
        val binding = bindingKey === v
        ui.card(kb, if (binding) RED.copy(a = 0.22f) else if (ui.hover(kb)) Theme.CARD_HOVER else Theme.CARD, 7f)
        if (binding) ui.ctx.drawShape(kb, ShapeStyle(Color(0f, 0f, 0f, 0f), 7f, 1f, RED))
        val name = when {
            binding -> I18n.text("Press a key")
            v.value == 0 -> I18n.text("None")
            else -> Keys.name(v.value) ?: "#${v.value}"
        }
        val s = ui.trim(name, kb.width - 10f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(s, kb.x + (kb.width - ui.width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f, cy - 11f * 0.39f - 0.5f, 11f,
            if (binding) RED else if (v.value == 0) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val hb = kb.intersect(clip)
        keyButtons += hb
        ui.hits += hb to { bindingKey = if (bindingKey === v) null else v }

        val reset = LayoutBounds(kb.right + 6f, kb.y, 52f, kb.height)
        ui.card(reset, if (ui.hover(reset)) Theme.CARD_HOVER else Theme.CARD, 7f)
        val label = I18n.text("Reset")
        ui.text(label, reset.x + (reset.width - ui.width(label, 11f, Theme.FONT_MEDIUM)) / 2f,
            cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        val resetHit: Hit = reset.intersect(clip) to {
            bindingKey = null
            module.resetKeyBinding(v)
        }
        keyResetButtons += resetHit
        ui.hits += resetHit
    }

    /** 重置优先于鼠标录制，避免点击重置时反而绑定成鼠标左键。 */
    fun resetKeyAt(x: Int, y: Int): Boolean {
        val hit = keyResetButtons.firstOrNull { it.first.contains(x, y) } ?: return false
        commitEdit()
        screen.blurTextFields()
        hit.second.invoke()
        return true
    }

    private fun applyDrag() {
        val v = dragValue ?: return
        if (dragTrack.width <= 0f) return
        val p = ((ui.mx - dragTrack.x) / dragTrack.width).coerceIn(0f, 1f)
        when (v) {
            is IntValue -> v.setValue((v.minRange + p * (v.maxRange - v.minRange)).roundToInt())
            is FloatValue -> v.setValue(v.minRange + p * (v.maxRange - v.minRange))
        }
    }

    // ---------------------------------------------------------------- 按键

    /** 正在输入数值或录快捷键时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        // 正在输入数值：Enter 确定，Esc 取消
        if (editRow != null) {
            when {
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> commitEdit()
                keyCode == Keys.ESCAPE -> editRow = null
                keyCode == Keys.BACK -> if (editText.isNotEmpty()) editText = editText.dropLast(1)
                DangoClient.platform.ctrlDown && keyCode == Keys.A -> editText = ""
                (typedChar.isDigit() || typedChar == '.' || typedChar == ',' || typedChar == '-') && editText.length < 10 ->
                    editText += typedChar
            }
            return true
        }
        // 正在录按键：Esc 取消；Backspace / Delete 清空。
        val v = bindingKey ?: return false
        when {
            keyCode == Keys.ESCAPE -> {}
            (keyCode == Keys.BACK || keyCode == Keys.DELETE) && v.minRange <= 0 -> v.setValue(0)
            keyCode != 0 -> v.setValue(keyCode)
        }
        bindingKey = null
        return true
    }

    fun bindMouse(button: Int): Boolean {
        val value = bindingKey ?: return false
        value.setValue(Keys.mouse(button))
        bindingKey = null
        return true
    }
}
