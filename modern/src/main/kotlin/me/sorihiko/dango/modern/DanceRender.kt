package me.sorihiko.dango.modern

import com.mojang.blaze3d.vertex.PoseStack
import me.sorihiko.dango.music.Dance
import kotlin.math.sqrt

/**
 * 生物跟着节奏跳（设置 → Lyrics → 跳舞）：把 [Dance] 算好的姿势套到画生物用的矩阵上。
 * 原点要在生物脚下（压扁时脚不离地）。
 */
object DanceRender {
    /** Fabric / 26.x：原版 setupRotations 之后调用，这时坐标轴已经跟着身体朝向 */
    @JvmStatic
    fun apply(poseStack: PoseStack) {
        val pose = Dance.pose() ?: return
        if (pose[0] != 0f) {
            // 直接转矩阵：26.3 把 mulPose(Quaternionf) 改名成了 rotate，矩阵本身各版本都一样
            val rad = Math.toRadians(pose[0].toDouble()).toFloat()
            val last = poseStack.last()
            last.pose().rotateY(rad)
            last.normal().rotateY(rad)
        }
        squash(poseStack, pose[1])
    }

    /** 上下压扁 / 拉长，左右反过来缩放（体积不变） */
    @JvmStatic
    fun squash(poseStack: PoseStack, squash: Float) {
        val wide = 1f / sqrt(squash)
        poseStack.scale(wide, squash, wide)
    }
}
