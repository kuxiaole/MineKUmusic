package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoLogger

import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/** Java 25 及以上禁用本 Mod 的 C2 编译和 C2 内联，规避已观察到的界面编译器崩溃。 */
object JitWorkaround {
    fun install() {
        val java = try { Runtime.version().feature() } catch (_: Throwable) { 0 }
        if (java < 25) return
        try {
            val result = installDirectives()
            DangoLogger.info("MineKU Music: JIT workaround installed (Java {}): {}", java, result.trim())
        } catch (t: Throwable) {
            DangoLogger.warn("MineKU Music: JIT workaround NOT installed (Java {}): {}", java, t.toString())
        }
    }

    internal fun installDirectives(command: (String) -> String = ::addDirectives): String {
        // HotSpot 在原生层打开文件。只传工作目录中的 ASCII 文件名，避免绝对路径的
        // Windows 编码问题和诊断命令对空格的解析；不依赖系统临时目录的路径。
        val file = File.createTempFile("minekumusic-jit-", ".json", File("."))
        try {
            file.writeText(
                """[
                  { match: ["me/sorihiko/dango/*.*"], c2: { Exclude: true } },
                  { match: ["*.*"], c2: { inline: ["-me/sorihiko/dango/*.*"] } }
                ]""",
                Charsets.US_ASCII,
            )
            val result = command(file.name)
            // 诊断命令通常用返回文字报告失败，不会抛异常。两条规则都被接受才算成功。
            check(result.lineSequence().any { it.trim() == "2 compiler directives added" }) {
                "JVM did not accept both C2 directives: ${result.trim()}"
            }
            return result
        } finally {
            if (!file.delete()) file.deleteOnExit()
        }
    }

    private fun addDirectives(fileName: String): String =
        ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "compilerDirectivesAdd",
            arrayOf<Any>(arrayOf(fileName)),
            arrayOf(Array<String>::class.java.name),
        ).toString()
}
