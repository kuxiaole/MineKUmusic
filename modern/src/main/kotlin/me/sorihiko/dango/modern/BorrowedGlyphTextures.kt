package me.sorihiko.dango.modern

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL33

/** 游戏和字体模组拥有的贴图：临时修改后必须恢复真实原值，不能假定默认通道布局。 */
class BorrowedGlyphTextures : AutoCloseable {
    private val previousTexture = Gl.currentTexture()
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

    override fun close() {
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
