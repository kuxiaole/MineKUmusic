package me.sorihiko.dango.music

import com.google.gson.JsonObject
import java.net.CookieManager

/** One explicit account context per caller; never consults client storage. */
class QqApi(private val account: NativeAccount?) {
    fun cgi(module: String, method: String, params: JsonObject): JsonObject {
        val payload = JsonObject().apply {
            add("comm", JsonObject().apply {
                addProperty("ct", 19); addProperty("cv", 2201)
                if (account != null) {
                    val key = CookieValues.cookie(account.cookies, "qqmusic_key")
                    addProperty("uin", account.userId); addProperty("authst", key)
                    addProperty("g_tk", CookieValues.hash33(key)); addProperty("tmeLoginType", account.loginType)
                }
            })
            add("req_0", JsonObject().apply { addProperty("module", module); addProperty("method", method); add("param", params) })
        }
        val root = AuthHttp.request(MusicSource.QQ, "https://u.y.qq.com/cgi-bin/musicu.fcg",
            account?.cookies ?: CookieManager(), payload.toString(), "application/json").json()
        val block = root.obj("req_0")
        if (!root.has("code") || root.number("code") != 0L || block == null || !block.has("code") || block.number("code") != 0L)
            throw SourceAccessException("QQ Music request failed. Please retry or log in again.")
        val data = block.obj("data") ?: throw SourceAccessException("QQ Music returned an incomplete response. Please retry.")
        if (data.has("code") && data.number("code") != 0L) throw SourceAccessException("QQ Music request failed. Please retry or log in again.")
        return data
    }

    private fun json(url: String): JsonObject = if (account == null) SourceHttp.json(url, "https://y.qq.com/")
        else AuthHttp.request(MusicSource.QQ, url, account.cookies).json()

    class Search(val tracks: List<NeteaseTrack>, val hasNext: Boolean)
    fun search(query: String, page: Int, size: Int): Search {
        require(page in 0..100 && size in 1..40)
        val response = json("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&p=${page + 1}&n=$size&w=${SourceHttp.enc(query)}")
        if (!response.has("code") || response.number("code") != 0L) throw SourceAccessException("QQ Music search failed. Please retry.")
        val songs = response.obj("data")?.obj("song") ?: return Search(emptyList(), false)
        val tracks = songs.array("list").mapNotNull { if (it.isJsonObject) parseTrack(it.asJsonObject) else null }
        return Search(tracks, songs.number("totalnum") > (page + 1L) * size)
    }

    fun detail(mid: String): NeteaseTrack {
        require(mid.matches(Regex("[A-Za-z0-9]{1,64}")))
        val data = cgi("music.pf_song_detail_svr", "get_song_detail_yqq", JsonObject().apply { addProperty("song_mid", mid) })
        return data.obj("track_info")?.let(::parseTrack)?.takeIf { it.nativeId == mid }
            ?: throw SourceAccessException("QQ Music song not found")
    }
    fun audio(track: NeteaseTrack): SourceAudio? = QqPlayback().resolve(track, account)
    fun lyrics(mid: String): List<SourceLyricLine> {
        try {
            val data = cgi("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", JsonObject().apply {
                addProperty("songMid", mid); addProperty("type", 1)
                for (key in listOf("lrc_t", "qrc_t", "roma", "roma_t", "trans", "trans_t")) addProperty(key, 0)
                addProperty("crypt", 1); addProperty("qrc", 1)
            })
            val lines = QqLyrics.decode(data.text("lyric"))
            if (lines.isNotEmpty()) return lines
        } catch (_: Exception) { }
        return SourceLyrics.parseLrc(json("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=${SourceHttp.enc(mid)}").text("lyric"))
    }
    companion object {
        @JvmStatic fun parseTrack(song: JsonObject): NeteaseTrack? {
            val mid = song.text("songmid").ifEmpty { song.text("mid") }
            if (!mid.matches(Regex("[A-Za-z0-9]{1,64}"))) return null
            val album = song.text("albummid").ifEmpty { song.obj("album")?.text("mid").orEmpty() }
            return NeteaseTrack(SourceIdentity.stableId(MusicSource.QQ, mid),
                cleanSourceText(song.text("songname").ifEmpty { song.text("title").ifEmpty { song.text("name") } }),
                song.array("singer").mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.text("name")?.let(::cleanSourceText) },
                song.number("interval") * 1000,
                if (album.isEmpty()) "" else "https://y.gtimg.cn/music/photo_new/T002R300x300M000$album.jpg",
                MusicSource.QQ, mid, song.text("strMediaMid").ifEmpty { song.text("media_mid").ifEmpty {
                    song.obj("file")?.text("media_mid").orEmpty().ifEmpty { mid }
                } }, album)
        }
    }
}
