package me.sorihiko.dango.modern

import me.sorihiko.dango.font.CjkFont

import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

import java.nio.ByteBuffer

/*
 * 26.x 选了 Vulkan 时：原版不用 OpenGL，我们自己开一个看不见的 OpenGL 环境，东西照旧用 OpenGL 画，
 * 画完整张读回内存，再交给原版（Vulkan）贴到屏幕上。这里是只用 OpenGL 的那部分（各版本通用）；
 * 和原版打交道的部分（建环境、原版贴图、读回游戏画面）在各 26.x 版本的 McCompat 里。
 */

/** 本帧有没有用到玻璃（用到了下一帧才去读回游戏画面给玻璃当背景，平时省掉） */
internal object OffscreenHints {
    @JvmField var glassUsed = false
}

/** 我们 OpenGL 环境里的一张画布：一个帧缓冲 + 一张贴图，可以整张读回内存、或从内存整张写进来 */
internal class GlCanvas {
    var framebuffer = 0
        private set
    var texture = 0
        private set
    var width = 0
        private set
    var height = 0
        private set
    private var pixels: ByteBuffer? = null

    fun ensure(w: Int, h: Int) {
        if (w == width && h == height && framebuffer != 0) return
        if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
        if (texture != 0) GL11.glDeleteTextures(texture)
        texture = GL11.glGenTextures()
        val prev = Gl.currentTexture()
        Gl.bindTexture(texture)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE)
        Gl.bindTexture(prev)
        val previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous)
        pixels = BufferUtils.createByteBuffer(w * h * 4)
        width = w
        height = h
    }

    /** 画之前：画到这张画布上，清成全透明 */
    fun begin() {
        McCompat.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
        Gl.viewport(0, 0, width, height)
        Gl.scissor(false)
        Gl.colorMask(true, true, true, true)
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, TRANSPARENT)
    }

    /** 整张读回内存（会等显卡画完）；和 OpenGL 一样从最下面一行开始 */
    fun readBack(): ByteBuffer {
        Imm.flush()
        val buf = pixels!!
        buf.clear()
        val prev = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev)
        buf.rewind()
        return buf
    }

    /** 从内存整张写进来（行的顺序和 [readBack] 一样） */
    fun write(data: ByteBuffer) {
        val prev = Gl.currentTexture()
        Gl.bindTexture(texture)
        Gl.withDefaultUnpack {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data)
        }
        Gl.bindTexture(prev)
    }

    companion object {
        private const val CLAMP_TO_EDGE = 0x812F
        private val TRANSPARENT = floatArrayOf(0f, 0f, 0f, 0f)

        /** 把内存里的 RGBA 像素（[w]×[h]）建成我们自己 OpenGL 环境里的贴图（[nearest]：放大时保持像素感） */
        fun texture(w: Int, h: Int, abgr: IntArray, nearest: Boolean = false): ModernTexture {
            val pixels = BufferUtils.createByteBuffer(w * h * 4)
            pixels.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(abgr)   // ABGR 整数按小端存就是 R G B A
            val id = GL11.glGenTextures()
            val prev = Gl.currentTexture()
            Gl.bindTexture(id)
            Gl.withDefaultUnpack {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
            }
            val filter = if (nearest) GL11.GL_NEAREST else GL11.GL_LINEAR
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE)
            Gl.bindTexture(prev)
            return ModernTexture({ id }, { GL11.glDeleteTextures(id) })
        }
    }
}

/**
 * 主菜单的全景背景（原版的全景是 Vulkan 画的，玻璃拿不到，所以我们用同样的六张图自己画一份）。
 * 和原版 CubeMap 一样：85° 视角，先翻转再按俯仰、旋转角转。
 */
internal object GlPanorama {
    /** 六个面的四个角（和原版 CubeMap 一样的顺序），每个角的贴图坐标依次是 (0,0) (0,1) (1,1) (1,0) */
    private val FACES = arrayOf(
        floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, 1f),
        floatArrayOf(1f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, 1f, -1f, -1f),
        floatArrayOf(1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f, -1f, -1f, -1f, -1f),
        floatArrayOf(-1f, -1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f),
        floatArrayOf(-1f, -1f, -1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, -1f),
        floatArrayOf(-1f, 1f, 1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f),
    )

    private val view = Matrix4f()
    private val face = Matrix4f()

    /** 画在当前绑定的帧缓冲上（铺满 [w]×[h]）；[faces] 是六张图，[overlay] 是原版盖在上面的暗角 */
    fun draw(faces: List<ModernTexture>, overlay: ModernTexture?, pitch: Float, yaw: Float, w: Int, h: Int) {
        if (faces.size < 6) return
        val savedPose = Matrix4f(Fixed.pose)
        Gl.blend(false)
        Gl.depthTest(false)
        Gl.cull(false)
        McCompat.pushScreenProjection(Matrix4f().setPerspective(Math.toRadians(85.0).toFloat(), w.toFloat() / h, 0.05f, 10f))
        McCompat.pushIdentityModelView()
        try {
            view.rotationX(Math.PI.toFloat())
                .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
                .rotateY(Math.toRadians(yaw.toDouble()).toFloat())
            Fixed.color(1f, 1f, 1f, 1f)
            for (i in 0 until 6) {
                val c = FACES[i]
                // 把 (u, v) ∈ [0,1]² 映射到这个面：角 0 + u·(角 3 − 角 0) + v·(角 1 − 角 0)
                face.set(
                    c[9] - c[0], c[10] - c[1], c[11] - c[2], 0f,
                    c[3] - c[0], c[4] - c[1], c[5] - c[2], 0f,
                    0f, 0f, 0f, 0f,
                    c[0], c[1], c[2], 1f,
                )
                Fixed.pose.set(view).mul(face)
                faces[i].bind()
                Imm.begin()
                Imm.tex(0f, 0f); Imm.vertex(0f, 0f)
                Imm.tex(0f, 1f); Imm.vertex(0f, 1f)
                Imm.tex(1f, 1f); Imm.vertex(1f, 1f)
                Imm.tex(1f, 0f); Imm.vertex(1f, 0f)
                Imm.endFixed()
            }
        } finally {
            McCompat.popModelView()
            McCompat.popScreenProjection()
            Fixed.pose.set(savedPose)
        }
        if (overlay != null) MenuPanoramaOverlay.draw(overlay, w, h)
    }
}

/**
 * 原版的像素字贴图在 Vulkan 那边，我们读不到：用原版字体用的同一批点阵图（ascii.png 等，资源包也会生效）自己画。
 * 每个字的宽度直接问原版，排版和原版完全一样；点阵图里没有的字（比如中文）用平滑字体，居中放在原版给的宽度里。
 */
internal class PixelFont(private val sheets: List<Sheet>) {
    /** 一张点阵图：每个格子一个字，[height] 是字在屏幕上的高度（原版默认 8），[ascent] 是基线以上的高度 */
    class Sheet(
        val texture: ModernTexture,
        val cellW: Int, val cellH: Int, val imageW: Int, val imageH: Int,
        val height: Int, val ascent: Int,
        /** 字 → (列, 行, 这个字实际有几列像素) */
        val glyphs: Map<Int, IntArray>,
    )

    private val savedPose = Matrix4f()

    /** 用原版的方式排一行字：[advance] 是原版给的每个字的宽度 */
    fun draw(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f, advance: (String) -> Int) {
        savedPose.set(Fixed.pose)
        val c = argb
        Fixed.color((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f, (c ushr 24) / 255f)
        Fixed.pose.set(pose)
        try {
            var pen = x
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = Character.charCount(cp)
                if (cp == 0xA7 && i + n < text.length) {   // § 格式代码：跳过它和后面那个字
                    i += n + Character.charCount(text.codePointAt(i + n))
                    continue
                }
                val s = text.substring(i, i + n)
                val adv = advance(s).toFloat()
                if (!drawGlyph(cp, pen, y) && !Character.isWhitespace(cp)) {
                    val w = CjkFont.width(s, 9f, CjkFont.PLAIN)
                    CjkFont.draw(s, pen + (adv - w) / 2f, y + 7.5f, 9f, CjkFont.PLAIN)
                }
                pen += adv
                i += n
            }
        } finally {
            Fixed.pose.set(savedPose)
        }
    }

    private fun drawGlyph(cp: Int, x: Float, y: Float): Boolean {
        for (sheet in sheets) {
            val g = sheet.glyphs[cp] ?: continue
            if (g[2] <= 0) return true   // 空白字
            val scale = sheet.height.toFloat() / sheet.cellH
            val top = y + 7 - sheet.ascent
            val w = g[2] * scale
            val h = sheet.height.toFloat()
            val u0 = g[0] * sheet.cellW.toFloat() / sheet.imageW
            val v0 = g[1] * sheet.cellH.toFloat() / sheet.imageH
            val u1 = (g[0] * sheet.cellW + g[2]).toFloat() / sheet.imageW
            val v1 = (g[1] + 1) * sheet.cellH.toFloat() / sheet.imageH
            sheet.texture.bind()
            Imm.begin()
            Imm.tex(u0, v0); Imm.vertex(x, top)
            Imm.tex(u0, v1); Imm.vertex(x, top + h)
            Imm.tex(u1, v1); Imm.vertex(x + w, top + h)
            Imm.tex(u1, v0); Imm.vertex(x + w, top)
            Imm.endFixed()
            return true
        }
        return false
    }

    companion object {
        /**
         * 按原版字体定义（font/include/default.json 里的 bitmap 项）建；[image] 按文件名读一张图，返回 (ABGR 像素, 宽, 高)。
         * 每个字有几列像素和原版的算法一样：从右往左找第一列有不透明像素的。
         */
        fun load(json: String, image: (String) -> Triple<IntArray, Int, Int>?): PixelFont? {
            val root = com.google.gson.JsonParser.parseString(json).asJsonObject
            val sheets = ArrayList<Sheet>()
            for (p in root.getAsJsonArray("providers")) {
                val o = p.asJsonObject
                if (o.get("type")?.asString != "bitmap") continue
                val file = o.get("file")?.asString ?: continue
                val rows = o.getAsJsonArray("chars")?.map { it.asString } ?: continue
                if (rows.isEmpty()) continue
                val (pixels, iw, ih) = image(file) ?: continue
                val cols = rows[0].codePointCount(0, rows[0].length)
                if (cols <= 0) continue
                val cellW = iw / cols
                val cellH = ih / rows.size
                val glyphs = HashMap<Int, IntArray>()
                rows.forEachIndexed { r, line ->
                    var col = 0
                    var k = 0
                    while (k < line.length) {
                        val cp = line.codePointAt(k)
                        k += Character.charCount(cp)
                        if (cp != 0 && !glyphs.containsKey(cp)) {
                            var width = 0
                            for (cx in cellW - 1 downTo 0) {
                                var solid = false
                                for (cy in 0 until cellH) {
                                    if ((pixels[(r * cellH + cy) * iw + col * cellW + cx] ushr 24) != 0) {
                                        solid = true
                                        break
                                    }
                                }
                                if (solid) {
                                    width = cx + 1
                                    break
                                }
                            }
                            glyphs[cp] = intArrayOf(col, r, width)
                        }
                        col++
                    }
                }
                val texture = GlCanvas.texture(iw, ih, pixels, nearest = true)
                sheets += Sheet(texture, cellW, cellH, iw, ih, o.get("height")?.asInt ?: 8, o.get("ascent")?.asInt ?: 7, glyphs)
            }
            return if (sheets.isEmpty()) null else PixelFont(sheets)
        }
    }
}

/** 点阵图都读不到时的退路：平滑字体，横向拉到和原版一样宽 */
internal object PixelTextFallback {
    private val savedPose = Matrix4f()
    private val FORMAT = Regex("§.")

    fun draw(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f, vanillaWidth: Int) {
        val plain = text.replace(FORMAT, "")
        if (plain.isBlank()) return
        val size = 9f
        val w = CjkFont.width(plain, size, CjkFont.PLAIN)
        if (w <= 0f) return
        val sx = if (vanillaWidth > 0) vanillaWidth / w else 1f
        savedPose.set(Fixed.pose)
        val c = argb
        Fixed.color((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f, (c ushr 24) / 255f)
        Fixed.pose.set(pose).translate(x, y, 0f).scale(sx, 1f, 1f)
        try {
            CjkFont.draw(plain, 0f, 7.5f, size, CjkFont.PLAIN)
        } finally {
            Fixed.pose.set(savedPose)
        }
    }
}
