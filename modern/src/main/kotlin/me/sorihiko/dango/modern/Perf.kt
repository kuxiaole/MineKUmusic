package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoLogger

import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33

/**
 * 性能测量（调试用，启动参数加 -Ddangomusic.perf=1 才打开）：
 * 每段记 CPU 时间和显卡时间（显卡用时间戳查询，几帧后再读结果，不会让 CPU 等显卡），每 5 秒在日志里报一次平均值。
 */
internal object Perf {
    @JvmField val enabled = System.getProperty("dangomusic.perf") != null

    private class Section(val name: String) {
        var cpuNs = 0L
        var gpuNs = 0L
        var gpuCount = 0
        var count = 0
        var draws = 0L
        var startNs = 0L
        var startDraws = 0
        val pending = ArrayDeque<IntArray>()
        var open: IntArray? = null
    }

    private val sections = LinkedHashMap<String, Section>()
    private var since = 0L
    private var frames = 0

    fun begin(name: String) {
        if (!enabled) return
        Imm.flush()
        val s = sections.getOrPut(name) { Section(name) }
        s.startNs = System.nanoTime()
        s.startDraws = Imm.glDraws
        val q = IntArray(2)
        q[0] = GL15.glGenQueries()
        q[1] = GL15.glGenQueries()
        GL33.glQueryCounter(q[0], GL33.GL_TIMESTAMP)
        s.open = q
    }

    fun end(name: String) {
        if (!enabled) return
        Imm.flush()
        val s = sections[name] ?: return
        val q = s.open ?: return
        s.open = null
        GL33.glQueryCounter(q[1], GL33.GL_TIMESTAMP)
        s.pending.addLast(q)
        s.cpuNs += System.nanoTime() - s.startNs
        s.draws += (Imm.glDraws - s.startDraws)
        s.count++
        collect(s)
    }

    /** 读已经出结果的显卡时间戳 */
    private fun collect(s: Section) {
        while (s.pending.isNotEmpty()) {
            val q = s.pending.first()
            if (GL15.glGetQueryObjecti(q[1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) break
            val t0 = GL33.glGetQueryObjecti64(q[0], GL15.GL_QUERY_RESULT)
            val t1 = GL33.glGetQueryObjecti64(q[1], GL15.GL_QUERY_RESULT)
            s.gpuNs += t1 - t0
            s.gpuCount++
            GL15.glDeleteQueries(q[0])
            GL15.glDeleteQueries(q[1])
            s.pending.removeFirst()
        }
    }

    /** 每帧调用一次（画界面之前）：到 5 秒就报一次 */
    fun frame() {
        if (!enabled) return
        frames++
        val now = System.nanoTime()
        if (since == 0L) since = now
        if (now - since < 5_000_000_000L) return
        val fps = frames / ((now - since) / 1e9)
        val sb = StringBuilder()
        sb.append(String.format("Dango perf: %.0f fps (%.2f ms/frame)", fps, 1000.0 / fps))
        for (s in sections.values) {
            if (s.count == 0) continue
            sb.append(String.format(" | %s: cpu %.2f ms, gpu %.2f ms, %d draws (x%.1f per frame)", s.name,
                s.cpuNs / 1e6 / s.count, if (s.gpuCount > 0) s.gpuNs / 1e6 / s.gpuCount else 0.0, s.draws / s.count, s.count.toDouble() / frames))
            s.cpuNs = 0; s.gpuNs = 0; s.gpuCount = 0; s.count = 0; s.draws = 0
        }
        DangoLogger.info(sb.toString())
        frames = 0
        since = now
    }
}
