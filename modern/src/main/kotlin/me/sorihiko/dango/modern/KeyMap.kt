package me.sorihiko.dango.modern

/**
 * 统一键码（LWJGL 2 的数值，见 [me.sorihiko.dango.platform.Keys]）和 GLFW 键码互相转换。
 * 设置文件里存的是统一键码，所以 1.8.9 和高版本的快捷键设置可以通用。
 */
object KeyMap {
    private val toGlfw = HashMap<Int, Int>()
    private val fromGlfw = HashMap<Int, Int>()

    private fun map(lwjgl2: Int, glfw: Int) {
        toGlfw[lwjgl2] = glfw
        fromGlfw[glfw] = lwjgl2
    }

    init {
        map(1, GlfwKeys.GLFW_KEY_ESCAPE)
        // 数字键 1-9, 0
        for (i in 0..8) map(2 + i, GlfwKeys.GLFW_KEY_1 + i)
        map(11, GlfwKeys.GLFW_KEY_0)
        map(12, GlfwKeys.GLFW_KEY_MINUS); map(13, GlfwKeys.GLFW_KEY_EQUAL)
        map(14, GlfwKeys.GLFW_KEY_BACKSPACE); map(15, GlfwKeys.GLFW_KEY_TAB)
        "QWERTYUIOP".forEachIndexed { i, c -> map(16 + i, GlfwKeys.GLFW_KEY_A + (c - 'A')) }
        map(26, GlfwKeys.GLFW_KEY_LEFT_BRACKET); map(27, GlfwKeys.GLFW_KEY_RIGHT_BRACKET)
        map(28, GlfwKeys.GLFW_KEY_ENTER); map(29, GlfwKeys.GLFW_KEY_LEFT_CONTROL)
        "ASDFGHJKL".forEachIndexed { i, c -> map(30 + i, GlfwKeys.GLFW_KEY_A + (c - 'A')) }
        map(39, GlfwKeys.GLFW_KEY_SEMICOLON); map(40, GlfwKeys.GLFW_KEY_APOSTROPHE); map(41, GlfwKeys.GLFW_KEY_GRAVE_ACCENT)
        map(42, GlfwKeys.GLFW_KEY_LEFT_SHIFT); map(43, GlfwKeys.GLFW_KEY_BACKSLASH)
        "ZXCVBNM".forEachIndexed { i, c -> map(44 + i, GlfwKeys.GLFW_KEY_A + (c - 'A')) }
        map(51, GlfwKeys.GLFW_KEY_COMMA); map(52, GlfwKeys.GLFW_KEY_PERIOD); map(53, GlfwKeys.GLFW_KEY_SLASH)
        map(54, GlfwKeys.GLFW_KEY_RIGHT_SHIFT); map(55, GlfwKeys.GLFW_KEY_KP_MULTIPLY)
        map(56, GlfwKeys.GLFW_KEY_LEFT_ALT); map(57, GlfwKeys.GLFW_KEY_SPACE); map(58, GlfwKeys.GLFW_KEY_CAPS_LOCK)
        for (i in 0..9) map(59 + i, GlfwKeys.GLFW_KEY_F1 + i)          // F1-F10
        map(69, GlfwKeys.GLFW_KEY_NUM_LOCK); map(70, GlfwKeys.GLFW_KEY_SCROLL_LOCK)
        map(71, GlfwKeys.GLFW_KEY_KP_7); map(72, GlfwKeys.GLFW_KEY_KP_8); map(73, GlfwKeys.GLFW_KEY_KP_9)
        map(74, GlfwKeys.GLFW_KEY_KP_SUBTRACT)
        map(75, GlfwKeys.GLFW_KEY_KP_4); map(76, GlfwKeys.GLFW_KEY_KP_5); map(77, GlfwKeys.GLFW_KEY_KP_6)
        map(78, GlfwKeys.GLFW_KEY_KP_ADD)
        map(79, GlfwKeys.GLFW_KEY_KP_1); map(80, GlfwKeys.GLFW_KEY_KP_2); map(81, GlfwKeys.GLFW_KEY_KP_3)
        map(82, GlfwKeys.GLFW_KEY_KP_0); map(83, GlfwKeys.GLFW_KEY_KP_DECIMAL)
        map(87, GlfwKeys.GLFW_KEY_F11); map(88, GlfwKeys.GLFW_KEY_F12)
        for (i in 0..5) map(100 + i, GlfwKeys.GLFW_KEY_F13 + i)        // F13-F18
        map(113, GlfwKeys.GLFW_KEY_F19)
        map(141, GlfwKeys.GLFW_KEY_KP_EQUAL)
        map(156, GlfwKeys.GLFW_KEY_KP_ENTER); map(157, GlfwKeys.GLFW_KEY_RIGHT_CONTROL)
        map(181, GlfwKeys.GLFW_KEY_KP_DIVIDE); map(183, GlfwKeys.GLFW_KEY_PRINT_SCREEN)
        map(184, GlfwKeys.GLFW_KEY_RIGHT_ALT); map(197, GlfwKeys.GLFW_KEY_PAUSE)
        map(199, GlfwKeys.GLFW_KEY_HOME); map(200, GlfwKeys.GLFW_KEY_UP); map(201, GlfwKeys.GLFW_KEY_PAGE_UP)
        map(203, GlfwKeys.GLFW_KEY_LEFT); map(205, GlfwKeys.GLFW_KEY_RIGHT)
        map(207, GlfwKeys.GLFW_KEY_END); map(208, GlfwKeys.GLFW_KEY_DOWN); map(209, GlfwKeys.GLFW_KEY_PAGE_DOWN)
        map(210, GlfwKeys.GLFW_KEY_INSERT); map(211, GlfwKeys.GLFW_KEY_DELETE)
        map(219, GlfwKeys.GLFW_KEY_LEFT_SUPER); map(220, GlfwKeys.GLFW_KEY_RIGHT_SUPER); map(221, GlfwKeys.GLFW_KEY_MENU)
    }

    /** 统一键码 → GLFW；没有对应的键返回 -1 */
    fun toGlfw(key: Int): Int = toGlfw[key] ?: -1

    /** GLFW → 统一键码；没有对应的键返回 0 */
    fun fromGlfw(glfw: Int): Int = fromGlfw[glfw] ?: 0
}
