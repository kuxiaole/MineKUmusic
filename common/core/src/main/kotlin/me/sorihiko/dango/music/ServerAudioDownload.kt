package me.sorihiko.dango.music

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Channel audio never inherits the player's personal NetEase credentials. */
internal object ServerAudioDownload {
    fun read(address: String, active: () -> Boolean): ByteArray? {
        var url = URL(address)
        repeat(6) {
            if (!active() || url.protocol !in listOf("http", "https")) return null
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: return null)
                    return@repeat
                }
                if (status !in 200..299) return null
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    while (active()) {
                        val n = input.read(buffer)
                        if (n < 0) return out.toByteArray()
                        if (out.size() + n > 64 * 1024 * 1024) return null
                        out.write(buffer, 0, n)
                    }
                }
                return null
            } finally { connection.disconnect() }
        }
        return null
    }
}
