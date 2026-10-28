package me.sorihiko.dango.music

class SourceLyricWord(val startMs: Long, val durMs: Long, val text: String)
class SourceLyricLine(val timeMs: Long, val text: String, val words: List<SourceLyricWord>? = null)

object SourceLyrics {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val credit = Regex(
        """^\s*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|发行|统筹|词|曲|OP|SP|Lyricist|Lyrics by|Composer|Composed by|Producer|Produced by|Arranger|Arranged by)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    fun isCredit(text: String): Boolean = credit.containsMatchIn(text)

    fun parseLrc(text: String): List<SourceLyricLine> {
        val out = mutableListOf<SourceLyricLine>()
        for (raw in text.lines()) {
            val tags = mutableListOf<Long>()
            var rest = raw.trim()
            while (true) {
                val m = timeTag.find(rest) ?: break
                if (m.range.first != 0) break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                tags += min * 60_000 + sec * 1000 + frac
                rest = rest.substring(m.range.last + 1)
            }
            if (tags.isEmpty()) continue
            val lyric = rest.trim()
            if (credit.containsMatchIn(lyric)) continue
            for (t in tags) out += SourceLyricLine(t, lyric)
        }
        out.sortBy { it.timeMs }
        return out
    }


}
