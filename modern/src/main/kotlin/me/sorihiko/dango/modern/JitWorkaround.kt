package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoLogger

import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * 绕开 Java 25 的 C2 编译器 bug。
 *
 * Java 25 的 C2 编译器（把常用的方法优化成机器码的那个）在优化我们画界面的代码时会自己崩掉，
 * 整个游戏直接闪退（不是我们的代码出错，日志 hs_err 里是 "C2 CompilerThread" 崩溃）。
 * 26.x 本来就用 Java 25；1.21.x 有的启动器也会给它配 Java 25，一样会崩。
 *
 * 所以在 Java 25 及以上，启动时告诉 JVM：我们自己的代码不用 C2 优化，也不被 C2 内联进别的方法里
 * （只用 C1，画界面够快了）。Java 21 及以下没有这个问题，什么都不做。
 */
object JitWorkaround {
    fun install() {
        val java = try { Runtime.version().feature() } catch (_: Throwable) { 0 }
        if (java < 25) return
        try {
            val file = File.createTempFile("dangomusic-jit", ".json")
            file.deleteOnExit()
            file.writeText(
                """[
                  { match: ["me/sorihiko/dango/*.*"], c2: { Exclude: true } },
                  { match: ["*.*"], c2: { inline: ["-me/sorihiko/dango/*.*"] } }
                ]""",
            )
            val result = ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"),
                "compilerDirectivesAdd",
                arrayOf<Any>(arrayOf(file.absolutePath)),
                arrayOf(Array<String>::class.java.name),
            )
            DangoLogger.info("MineKU Music: JIT workaround installed (Java {}): {}", java, result.toString().trim())
        } catch (t: Throwable) {
            DangoLogger.warn("MineKU Music: cannot install JIT workaround: {}", t.toString())
        }
    }
}
