package me.sorihiko.dango.modern

import com.mojang.blaze3d.platform.InputConstants

import me.sorihiko.dango.platform.PlayerAction

import me.sorihiko.dango.platform.Keys

import net.minecraft.client.KeyMapping

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import org.joml.Matrix4fc
import org.lwjgl.opengl.GL30

/** 这个版本的 GlStateManager 在 platform 包里 */
internal typealias GlStateManager = com.mojang.blaze3d.platform.GlStateManager

/** 同步原版 GlStateManager 记的混合 / 颜色写入状态（各版本参数不一样） */
internal object GlCache {
    fun blend(on: Boolean) {
        if (on) GlStateManager._enableBlend() else GlStateManager._disableBlend()
    }

    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {
        GlStateManager._colorMask(r, g, b, a)
    }
}

/**
 * 1.21.1 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用，对外的样子完全一样。
 */
internal object McCompat {
    fun createPlayerKey(action: PlayerAction): KeyMapping =
        KeyMapping(action.translationKey, gameBindingKey(action.defaultKey).value, "key.categories.dangomusic")

    fun gameBindingKey(code: Int): InputConstants.Key = when {
        code == Keys.NONE -> InputConstants.UNKNOWN
        code < 0 -> InputConstants.Type.MOUSE.getOrCreate(code + 100)
        else -> InputConstants.Type.KEYSYM.getOrCreate(KeyMap.toGlfw(code))
    }

    fun commonBindingKey(key: InputConstants.Key): Int =
        if (key.type == InputConstants.Type.MOUSE) Keys.mouse(key.value) else KeyMap.fromGlfw(key.value)


    /** 把原版的视图矩阵临时换成单位矩阵（1.20.5 起是 JOML 的 Matrix4fStack） */
    fun pushIdentityModelView() {
        val mv = RenderSystem.getModelViewStack()
        mv.pushMatrix()
        mv.identity()
        RenderSystem.applyModelViewMatrix()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popMatrix()
        RenderSystem.applyModelViewMatrix()
    }

    /** 当前的投影矩阵（[Fixed] 算 MVP 用）：这些版本直接问原版要 */
    fun projection(): Matrix4f = RenderSystem.getProjectionMatrix()

    /** 换成自己的正交投影（画屏幕上的东西用），原版的字也跟着用它 */
    fun pushScreenProjection(m: Matrix4f) {
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(m, VertexSorting.ORTHOGRAPHIC_Z)
    }

    fun popScreenProjection() {
        RenderSystem.restoreProjectionMatrix()
    }

    /** 世界画完、开始画我们的东西之前（这些版本当前帧缓冲就是主画面，不用做什么） */
    fun beginWorld() {}

    /** 绑定帧缓冲（这些版本原版不记帧缓冲，直接绑） */
    fun bindFramebuffer(target: Int, id: Int) {
        GL30.glBindFramebuffer(target, id)
    }

    /** 让原版画一批东西（这些版本原版直接画在当前绑定的帧缓冲上，不用做什么） */
    inline fun vanillaDraw(block: () -> Unit) = block()

    /** 每次画之前（这些版本不用做什么） */
    fun beforeDraw() {}

    fun cameraXRot(): Float = camera().xRot
    fun cameraYRot(): Float = camera().yRot
    fun cameraPosition(): Vec3 = camera().position
    private fun camera() = Minecraft.getInstance().gameRenderer.mainCamera

    fun windowHandle(): Long = Minecraft.getInstance().window.window

    /** 鼠标键 [button]（GLFW 编号：0 左、1 右、2 中）是否按着 */
    fun isMouseDown(button: Int): Boolean =
        org.lwjgl.glfw.GLFW.glfwGetMouseButton(windowHandle(), button) == org.lwjgl.glfw.GLFW.GLFW_PRESS

    /** 键盘键（GLFW 键码）是否按着 */
    fun isGlfwKeyDown(key: Int): Boolean =
        org.lwjgl.glfw.GLFW.glfwGetKey(windowHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS

    /** 原版视图矩阵的当前值（[Fixed] 算 MVP 用） */
    fun modelView(): Matrix4fc = RenderSystem.getModelViewMatrix()

    /** 用原版字体画一段像素字（[pose] 是模型矩阵），画在当前绑定的帧缓冲上 */
    fun drawText(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f) {
        val mc = Minecraft.getInstance()
        val buffers = mc.renderBuffers().bufferSource()
        mc.font.drawInBatch(
            text, x, y, argb, false, pose, buffers,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT,
        )
        vanillaDraw { buffers.endBatch() }
    }

    fun currentScreen(): Screen? = Minecraft.getInstance().screen

    fun setScreen(screen: Screen?) {
        Minecraft.getInstance().setScreen(screen)
    }

    /** 能不能用 OpenGL 画（26.x 起可以选 Vulkan，那时我们画不了） */
    val glAvailable: Boolean get() = true

    /** 原版开始画界面之前（只有 26.x 用到） */
    fun beforeGui() {}

    /** 不能用 OpenGL 时提示玩家（这些版本一定能用） */
    fun notifyNoGl(again: Boolean = false) {}

    /** 新建一张贴图交给原版管理，[abgr] 是逐行的像素 */
    fun createTexture(name: String, w: Int, h: Int, abgr: IntArray): ModernTexture {
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixelRGBA(x, y, abgr[y * w + x])
        val texture = DynamicTexture(native)
        val manager = Minecraft.getInstance().textureManager
        val location = manager.register(name, texture)
        return ModernTexture({ texture.id }, { manager.release(location) })
    }
}

/** 界面基类：把原版界面的各种事件统一成简单的几个方法（1.20.2 起滚轮事件分横向 / 竖向） */
abstract class CompatScreen(title: Component) : Screen(title) {
    abstract fun onRender()
    abstract fun onMouseClicked(button: Int)
    abstract fun onMouseDragged()
    abstract fun onMouseReleased()
    abstract fun onScroll(delta: Double)
    abstract fun onKeyPressed(glfwKey: Int)
    abstract fun onKeyReleased(glfwKey: Int)
    abstract fun onCharTyped(c: Char)

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.flush()   // 先把原版排队的东西画掉，再画我们自己的
        onRender()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseClicked(button)
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        onMouseDragged()
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseReleased()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyPressed(keyCode)
        return true
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyReleased(keyCode)
        return true
    }

    override fun charTyped(c: Char, modifiers: Int): Boolean {
        onCharTyped(c)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        onScroll(scrollY)
        return true
    }
}
