package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricWord
import me.sorihiko.dango.lyrics.LyricsProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/** KRC has a four-byte header, repeating XOR mask and a zlib-compressed UTF-8 document. */
internal object KugouLyrics {
    private val mask = intArrayOf(0x40,0x47,0x61,0x77,0x5e,0x32,0x74,0x47,0x51,0x36,0x31,0x2d,0xce,0xd2,0x6e,0x69)
    fun decode(content: String): List<LyricLine> {
        require(content.length <= 3*1024*1024)
        val bytes = Base64.getDecoder().decode(content)
        require(bytes.size >= 4 && String(bytes,0,4,Charsets.US_ASCII) == "krc1")
        val compressed = ByteArray(bytes.size-4) { (bytes[it+4].toInt() xor mask[it%mask.size]).toByte() }
        val text = InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(out.size()+n <= 2*1024*1024); out.write(buffer,0,n)
            }
            String(out.toByteArray(),Charsets.UTF_8)
        }
        return parse(text)
    }
    fun parse(text: String): List<LyricLine> {
        val linePattern = Regex("^\\[(\\d+),(\\d+)](.*)$")
        val wordPattern = Regex("<(\\d+),(\\d+),\\d+>([^<]*)")
        return text.lineSequence().mapNotNull { raw ->
            val line = linePattern.matchEntire(raw.trim()) ?: return@mapNotNull null
            val start = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val words = wordPattern.findAll(line.groupValues[3]).mapNotNull { word ->
                val offset = word.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val duration = word.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                if (offset > Long.MAX_VALUE-start) null else LyricWord(start+offset,duration,word.groupValues[3])
            }.toList()
            val value = if (words.isEmpty()) line.groupValues[3] else words.joinToString("") { it.text }
            if (value.isBlank() || LyricsProvider.isCredit(value)) null else LyricLine(start,value,words.takeIf { it.isNotEmpty() })
        }.sortedBy { it.timeMs }.toList()
    }
}
