package me.sorihiko.dango.modern

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL33

/** 游戏和字体模组拥有的贴图：临时修改后必须恢复真实原值，不能假定默认通道布局。 */
class BorrowedGlyphTextures : AutoCloseable {
    private var previousTexture = Gl.currentTexture()
    private val originals = LinkedHashMap<Int, IntArray>()

    fun bind(texture: Int, gray: Boolean) {
        Gl.bindTexture(texture)
        if (originals.containsKey(texture)) return
        val values = IntArray(6)
        GL11.glGetTexParameteriv(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_RGBA, values)
        values[4] = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER)
        values[5] = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER)
        originals[texture] = values
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        if (gray) GL11.glTexParameteriv(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_RGBA,
            intArrayOf(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_RED))
    }

    companion object {
        private var pass: BorrowedGlyphTextures? = null

        /** 这一轮画图共用一个：改过的设置一直保持，等这一轮画完（[endPass]）才改回去 */
        fun forPass(): BorrowedGlyphTextures = pass ?: BorrowedGlyphTextures().also { pass = it }

        /** 一轮画完（原版自己画字之前）：把借来的贴图设置改回去，当前绑定的贴图不变 */
        fun endPass() {
            val p = pass ?: return
            pass = null
            p.previousTexture = Gl.currentTexture()
            p.close()
        }
    }

    override fun close() {
        Imm.flush()
        try {
            for ((texture, values) in originals) {
                Gl.bindTexture(texture)
                GL11.glTexParameteriv(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_RGBA, values.copyOf(4))
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, values[4])
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, values[5])
            }
        } finally {
            Gl.bindTexture(previousTexture)
        }
    }
}
