package me.sorihiko.dango.platform

import java.nio.file.Path

/**
 * 共用代码需要从游戏拿的东西。每个 Minecraft 版本的平台层各实现一份。
 *
 * 坐标约定：窗口像素，原点在左上角。
 * 按键约定：一律用 [Keys] 里的键码（和 LWJGL 2 相同），平台层负责和游戏自己的键码互相转换。
 */
interface Platform {

    /** 配置、登录凭证、缓存目录（.minecraft/config/dangomusic） */
    val configDir: Path

    /** 当前登录的玩家名 */
    val playerName: String

    /** 游戏语言代码；客户端早期设置尚未创建时返回 null，不能据此保存默认语言。 */
    val languageCode: String?

    // ---------------------------------------------------------------- 窗口 / 输入

    val windowWidth: Int
    val windowHeight: Int

    /** 鼠标位置（窗口像素，左上角为原点） */
    val mouseX: Int
    val mouseY: Int

    fun isMouseDown(button: Int): Boolean

    /** [key] 是 [Keys] 键码 */
    fun isKeyDown(key: Int): Boolean

    /** 设置对象创建后同步原版按键页面和模组内绑定。 */
    fun syncKeyBindings(bindings: List<PlayerKeyBinding>) {}

    val ctrlDown: Boolean

    var clipboard: String

    // ---------------------------------------------------------------- 游戏状态

    /** 在游戏画面里：世界已加载，并且没有打开任何界面 */
    val inGame: Boolean

    /** 原版主菜单（或其子类）；其他界面不接管播放器打开键。 */
    val inMainMenu: Boolean get() = false

    /** 聊天栏开着 */
    val chatOpen: Boolean

    /** 原版 GUI 缩放倍数（设置里的"界面尺寸"实际生效的值） */
    val guiScale: Int

    val fps: Int

    /** 设置里的视野（FOV，竖直方向，角度） */
    val fov: Float

    /** 到服务器的延迟（毫秒）；单人游戏或拿不到时是 0 */
    val pingMs: Int

    /** 玩家眼睛的位置和朝向；不在世界里时是 null */
    val player: PlayerPose?

    // ---------------------------------------------------------------- 界面

    /** 打开内置播放器 */
    fun openMusicScreen()
}

/** yaw / pitch 和原版一致（角度） */
class PlayerPose(
    val eyeX: Double,
    val eyeY: Double,
    val eyeZ: Double,
    val yaw: Float,
    val pitch: Float,
)
