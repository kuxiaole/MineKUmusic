package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.platform.Keys

import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 内置播放器在高版本里的外壳：把原版界面的事件转给共用的 [me.sorihiko.dango.music.MusicScreen]。
 * 各版本原版界面的方法签名不一样（画图、鼠标、键盘），由 [CompatScreen] 统一成下面这几个方法。
 */
class ModernMusicScreen(
    /** 关闭后回到哪个界面（null = 回到游戏） */
    private val parent: Screen?,
) : CompatScreen(Component.literal(DangoMusic.NAME)) {

    private val screen = DangoClient.newMusicScreen { McCompat.setScreen(parent) }

    override fun init() {
        screen.opened()
    }

    override fun isPauseScreen() = false

    override fun removed() {
        screen.removed()
        super.removed()
    }

    override fun onRender() {
        screen.render(McCompat.renderMenuBackdrop(parent))
    }

    override fun onMouseClicked(button: Int) {
        screen.mouseClicked(button)
    }

    override fun onMouseDragged() {
        screen.mouseDragged()
    }

    override fun onMouseReleased() {
        screen.mouseReleased()
    }

    /** 滚轮：[delta] 是竖直方向滚了几格 */
    override fun onScroll(delta: Double) {
        // 1.8.9 每格滚轮是 ±120；这里换算成一样的数
        val wheel = (delta * 120).toInt()
        if (wheel != 0) screen.scroll(wheel, ModernPlatform.mouseX, ModernPlatform.mouseY)
    }

    override fun onKeyPressed(glfwKey: Int) {
        val key = KeyMap.fromGlfw(glfwKey)
        if (key != Keys.NONE) screen.keyTyped('\u0000', key)
    }

    override fun onKeyReleased(glfwKey: Int) {
        val key = KeyMap.fromGlfw(glfwKey)
        if (key != Keys.NONE) screen.keyReleased(key)
    }

    override fun onCharTyped(c: Char) {
        // 打字单独一个事件（1.8.9 是和按键一起来的）：键码给 NONE
        screen.keyTyped(c, Keys.NONE)
    }
}
