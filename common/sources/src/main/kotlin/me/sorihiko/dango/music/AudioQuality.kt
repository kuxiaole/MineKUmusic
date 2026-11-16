package me.sorihiko.dango.music

/**
 * 音质档位。设置里选所有歌的默认档；播放栏上可以给正在放的这首临时换一档（换歌就回到默认）。
 * 要的档位拿不到（没会员、没有这个文件）时，各音源自动往下一档降，不会放不出来。
 */
enum class AudioQuality(val label: String) {
    STANDARD("Standard quality"), HIGH("High quality"), LOSSLESS("Lossless quality");

    /** 网易云 v1 接口的 level，从高到低 */
    val neteaseLevels: List<String> get() = when (this) {
        LOSSLESS -> listOf("lossless", "exhigh", "standard")
        HIGH -> listOf("exhigh", "standard")
        STANDARD -> listOf("standard")
    }

    /** QQ 音乐的文件（前缀, 扩展名），从高到低：F000 = FLAC，M800 = 320k，M500 = 128k */
    val qqFiles: List<Pair<String, String>> get() = when (this) {
        LOSSLESS -> listOf("F000" to "flac", "M800" to "mp3", "M500" to "mp3")
        HIGH -> listOf("M800" to "mp3", "M500" to "mp3")
        STANDARD -> listOf("M500" to "mp3")
    }

    /** 酷狗的 quality 参数，从高到低 */
    val kugouQualities: List<String> get() = when (this) {
        LOSSLESS -> listOf("flac", "320", "128")
        HIGH -> listOf("320", "128")
        STANDARD -> listOf("128")
    }
}
