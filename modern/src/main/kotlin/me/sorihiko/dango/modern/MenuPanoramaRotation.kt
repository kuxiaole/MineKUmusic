package me.sorihiko.dango.modern

import java.lang.reflect.Field

/** 新版全景只有一个 float 状态：旋转角。按类型定位，不依赖开发环境字段名。 */
internal object MenuPanoramaRotation {
    private val fields = HashMap<Class<*>, Field>()

    fun advance(panorama: Any, deltaTicks: Float, speed: Double): Float {
        val field = fields.getOrPut(panorama.javaClass) {
            panorama.javaClass.declaredFields.single {
                it.type == java.lang.Float.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }.apply { isAccessible = true }
        }
        val spin = (field.getFloat(panorama) + deltaTicks * speed.toFloat() * 0.1f) % 360f
        field.setFloat(panorama, spin)
        return -spin
    }
}
