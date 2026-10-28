package me.sorihiko.dango.music

/**
 * 歌单歌曲的加载进度：各音源分页取歌时在这里报告"已经拿到几首、一共几首"（不知道总数时为 0），
 * 界面据此画进度条。只对用 [track] 包起来的那个线程生效，别的线程报告了也没人收。
 */
object TrackLoadProgress {
    private val listener = ThreadLocal<((Int, Int) -> Unit)?>()

    fun <T> track(onProgress: (Int, Int) -> Unit, block: () -> T): T {
        listener.set(onProgress)
        try {
            return block()
        } finally {
            listener.remove()
        }
    }

    @JvmStatic
    fun report(loaded: Int, total: Int) {
        try {
            listener.get()?.invoke(loaded, total)
        } catch (_: Exception) {
        }
    }
}
