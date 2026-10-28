package me.sorihiko.dango.modern

import me.sorihiko.dango.ui.GlassRenderContext
import me.sorihiko.dango.ui.LayoutBounds

/** 新版原版覆盖层通常延迟绘制；玻璃采样前先把同一张覆盖层画到全景上。 */
internal object MenuPanoramaOverlay {
    private val canvas by lazy { GlassRenderContext() }

    fun draw(texture: ModernTexture, width: Int, height: Int) {
        canvas.begin(false, 0, 0)
        try {
            canvas.drawImage(LayoutBounds(0f, 0f, width.toFloat(), height.toFloat()), texture)
        } finally {
            canvas.end()
        }
    }
}
