package me.sorihiko.dango.modern

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

/**
 * 改 OpenGL 状态的地方都走这里：既真的调 GL，也同步更新 Minecraft 自己的状态缓存（GlStateManager）。
 *
 * 高版本 Minecraft 改状态前会先查缓存，缓存说"已经是这样了"就不调 GL。
 * 如果我们绕过它直接改 GL，缓存就和实际对不上，之后原版画东西会出错（比如混合没开、绑错贴图）。
 * （GlStateManager 在不同版本里包名不一样，由 McCompat 里的 typealias 指过去）
 */
object Gl {
    /**
     * 正在往一张透明的离屏贴图上画（1.21.6 起的界面就是这样画、再整张贴上去）。
     * 这时透明度要按"预乘"的方式叠：alpha 通道用 ONE 而不是 SRC_ALPHA，否则半透明的地方会变淡。
     */
    var premultipliedTarget = false

    fun blend(on: Boolean) {
        GlCache.blend(on)
        if (on) GL11.glEnable(GL11.GL_BLEND) else GL11.glDisable(GL11.GL_BLEND)
    }

    fun blendFunc(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) {
        val sa = if (premultipliedTarget && srcAlpha == GL11.GL_SRC_ALPHA) GL11.GL_ONE else srcAlpha
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, sa, dstAlpha)
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, sa, dstAlpha)
    }

    fun blendFunc(src: Int, dst: Int) = blendFunc(src, dst, src, dst)

    /** 普通透明混合（和 1.8.9 菜单里用的一样） */
    fun alphaBlend() {
        blend(true)
        blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun depthTest(on: Boolean) {
        if (on) GlStateManager._enableDepthTest() else GlStateManager._disableDepthTest()
        if (on) GL11.glEnable(GL11.GL_DEPTH_TEST) else GL11.glDisable(GL11.GL_DEPTH_TEST)
    }

    fun depthMask(on: Boolean) {
        GlStateManager._depthMask(on)
        GL11.glDepthMask(on)
    }

    fun cull(on: Boolean) {
        if (on) GlStateManager._enableCull() else GlStateManager._disableCull()
        if (on) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
    }

    fun scissor(on: Boolean) {
        if (on) GlStateManager._enableScissorTest() else GlStateManager._disableScissorTest()
        if (on) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

    fun scissorBox(x: Int, y: Int, w: Int, h: Int) {
        GlStateManager._scissorBox(x, y, w, h)
        GL11.glScissor(x, y, w, h)
    }

    fun viewport(x: Int, y: Int, w: Int, h: Int) {
        GlStateManager._viewport(x, y, w, h)
        GL11.glViewport(x, y, w, h)
    }

    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {
        GlCache.colorMask(r, g, b, a)
        GL11.glColorMask(r, g, b, a)
    }

    fun activeTexture(unit: Int) {
        GlStateManager._activeTexture(unit)
        GL13.glActiveTexture(unit)
    }

    /** 绑定到当前贴图单元 */
    fun bindTexture(id: Int) {
        GlStateManager._bindTexture(id)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
    }

    /** 新建一张贴图并绑定（Minecraft 的缓存也会知道） */
    fun genTexture(): Int {
        val id = GL11.glGenTextures()
        bindTexture(id)
        return id
    }

    fun deleteTexture(id: Int) {
        GlStateManager._deleteTexture(id)
    }

    fun currentTexture(): Int = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)

    /**
     * 往贴图里传像素时用默认的读取方式（逐行紧挨着、从头开始读），传完恢复原样。
     * 原版传贴图时会改这些设置而且不一定改回来（1.21.5 起尤其明显），不重置的话字会变成一堆错位的碎片。
     */
    fun <T> withDefaultUnpack(block: () -> T): T {
        val names = intArrayOf(
            GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
            GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS,
        )
        val saved = IntArray(names.size) { GL11.glGetInteger(names[it]) }
        val pbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        try {
            return block()
        } finally {
            for (i in names.indices) GL11.glPixelStorei(names[i], saved[i])
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo)
        }
    }
}

/**
 * 记下当前的 OpenGL 状态，[restore] 时原样恢复（同时同步 Minecraft 的缓存）。
 * 包住我们自己的一段绘制：前后原版的渲染状态完全不变。
 */
class GlState {
    private val program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
    private val vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
    private val arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
    private val drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
    private val readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
    private val activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
    private val binding = IntArray(2)
    private val blend = GL11.glIsEnabled(GL11.GL_BLEND)
    private val srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
    private val dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
    private val srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
    private val dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
    private val eqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB)
    private val eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)
    private val depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
    private val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
    private val cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
    private val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
    private val scissorBox = IntArray(4)
    private val viewport = IntArray(4)
    private val colorMask = ByteArray(4)

    init {
        for (i in 0..1) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
            binding[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        }
        GL13.glActiveTexture(activeTex)
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox)
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
        val mask = org.lwjgl.BufferUtils.createByteBuffer(4)
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask)
        for (k in 0..3) colorMask[k] = mask.get(k)
    }

    fun restore() {
        McCompat.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo)
        McCompat.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo)
        Gl.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GL20.glUseProgram(program)
        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)

        for (i in 1 downTo 0) {
            Gl.activeTexture(GL13.GL_TEXTURE0 + i)
            Gl.bindTexture(binding[i])
        }
        Gl.activeTexture(activeTex)

        Gl.blend(blend)
        Gl.blendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha)
        GL20.glBlendEquationSeparate(eqRgb, eqAlpha)
        Gl.depthTest(depthTest)
        Gl.depthMask(depthMask)
        Gl.cull(cull)
        Gl.scissor(scissor)
        Gl.scissorBox(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        Gl.colorMask(colorMask[0].toInt() != 0, colorMask[1].toInt() != 0, colorMask[2].toInt() != 0, colorMask[3].toInt() != 0)
    }
}
