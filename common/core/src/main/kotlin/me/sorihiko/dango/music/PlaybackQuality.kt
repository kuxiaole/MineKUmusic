package me.sorihiko.dango.music

object PlaybackQuality {
    /** 设置里的默认音质 */
    @Volatile var default = AudioQuality.HIGH
    /** 播放栏上给当前这首临时选的；换歌时清掉 */
    @Volatile var override: AudioQuality? = null
    val wanted: AudioQuality get() = override ?: default

    /**
     * 实际拿到的音质（显示用）：FLAC 就是无损；MP3 按文件大小和时长估码率，
     * 接近 320 / 192 / 128 的就显示成那一档
     */
    fun describe(bytes: ByteArray, durationMs: Long): String {
        if (Mp3Probe.kind(bytes) == "flac") return "FLAC"
        if (durationMs <= 0) return ""
        val kbps = bytes.size * 8.0 / durationMs
        return when {
            kbps >= 280 -> "320K"
            kbps >= 170 -> "192K"
            kbps >= 100 -> "128K"
            else -> "${Math.round(kbps)}K"
        }
    }
}
