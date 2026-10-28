package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import java.util.Base64

/** Kugou library and playback share the existing native login and source-aware player. */
object KugouMusicSource : MusicSourceApi {
    override val source = MusicSource.KUGOU
    private const val REFERER = "https://m.kugou.com/"
    private val catalog = KugouCatalog()
    override fun playlists() = catalog.playlists()
    override fun recommend() = catalog.recommend()
    override fun charts() = catalog.charts()
    override fun playlistTracks(id: Long) = catalog.tracks(id)

    override fun search(query: String): List<NeteaseTrack> {
        val root = SourceHttp.json("https://msearchretry.kugou.com/api/v3/search/song?format=json&page=1&pagesize=40&showtype=1&keyword=${SourceHttp.enc(query)}", REFERER)
        check(root.number("status") == 1L) { "Kugou search rejected" }
        return parseSearch(root)
    }

    internal fun parseSearch(root: JsonObject): List<NeteaseTrack> {
        val list = root.getAsJsonObject("data")?.getAsJsonArray("info") ?: return emptyList()
        return list.mapNotNull { entry -> if (entry.isJsonObject) parseTrack(entry.asJsonObject) else null }.distinctBy { it.key }
    }

    internal fun parseTrack(song: JsonObject): NeteaseTrack? {
        val hash = song.text("hash").ifEmpty { song.text("Hash").ifEmpty { song.text("filehash") } }.uppercase(java.util.Locale.ROOT)
        if (!hash.matches(Regex("[A-F0-9]{32}"))) return null
        val filename = song.text("filename").ifEmpty { song.text("FileName").ifEmpty { song.text("name") } }
        val title = song.text("songname").ifEmpty { filename.substringAfter(" - ",filename) }
        val names = song.array("singerinfo").mapNotNull { if (it.isJsonObject) it.asJsonObject.text("name").takeIf { s -> s.isNotBlank() } else null }
            .ifEmpty { song.array("authors").mapNotNull { if (it.isJsonObject) it.asJsonObject.text("author_name").takeIf { s -> s.isNotBlank() } else null } }
            .ifEmpty { song.text("singername").ifEmpty { if (" - " in filename) filename.substringBefore(" - ") else "" }.split("、").filter { it.isNotBlank() } }
        val ms = song.number("timelen").takeIf { it > 0 } ?: song.number("timeLen").takeIf { it > 0 }
            ?: song.number("duration")*1000
        val cover = song.text("imgurl").ifEmpty { song.text("album_sizable_cover").ifEmpty { song.obj("trans_param")?.text("union_cover").orEmpty() } }
        return NeteaseTrack(MusicSources.stableId(source,hash),cleanSourceText(title),names.map(::cleanSourceText),ms,
            KugouCatalog.image(cover),source,hash,hash,song.text("album_id").ifEmpty { song.text("albumId") })
    }

    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? {
        require(track.source == source && track.nativeId.matches(Regex("[A-Fa-f0-9]{32}")))
        val account = NativeAccounts.get(source)
        if (account != null) {
            val response = KugouWeb.songInfo(track, account)
            val data = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val cover = data.text("sizable_cover").ifEmpty { data.text("img") }.replace("{size}", "300")
            if (cover.isNotEmpty()) track.coverUrl = cover
            val url = data.text("play_url")
            if (response.number("status") != 1L || url.isEmpty()) return null
            val duration = data.number("timelength")
            return NeteaseApi.SongUrl(url, duration in 1 until track.durationMs - 5000)
        }
        val data = SourceHttp.json("https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=${track.nativeId}&album_id=${SourceHttp.enc(track.albumId)}", REFERER)
        if (track.coverUrl.isEmpty()) {
            track.coverUrl = data.text("album_img").ifEmpty { data.text("imgUrl") }.replace("{size}", "300")
        }
        if (data.number("status") != 1L) return null
        val url = data.text("url")
        if (url.isEmpty()) return null
        val format = data.text("extName")
        if (format.isNotEmpty() && !format.equals("mp3", true)) return null
        val trial = data.number("timeLength") in 1 until track.durationMs / 1000 - 5
        return NeteaseApi.SongUrl(url, trial)
    }

    override fun lyrics(track: NeteaseTrack): Lyrics? {
        val search = SourceHttp.json("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${SourceHttp.enc(track.nativeId)}&duration=${track.durationMs}", REFERER)
        val candidate = search.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject ?: return null
        try {
            val krc = SourceHttp.json("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8&id=${SourceHttp.enc(candidate.text("id"))}&accesskey=${SourceHttp.enc(candidate.text("accesskey"))}", REFERER)
            val lines = KugouLyrics.decode(krc.text("content"))
            if (lines.isNotEmpty()) return Lyrics(lines,source.label)
        } catch (_: Exception) { /* Not every recording has KRC; retain the line-timed fallback. */ }
        val root = SourceHttp.json("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=${SourceHttp.enc(candidate.text("id"))}&accesskey=${SourceHttp.enc(candidate.text("accesskey"))}", REFERER)
        val text = String(Base64.getDecoder().decode(root.text("content")), Charsets.UTF_8)
        val lines = LyricsProvider.parseLrc(text)
        return if (lines.isEmpty()) null else Lyrics(lines, source.label)
    }
}
