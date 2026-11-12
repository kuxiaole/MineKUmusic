package me.sorihiko.dango.music

import com.google.gson.JsonObject
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider

/** Native QQ Music client. MP3 only: the shared player uses JLayer. */
object QqMusicSource : MusicSourceApi {
    override val source = MusicSource.QQ
    private const val REFERER = "https://y.qq.com/"
    private val catalog = QqCatalog(::cgi, ::profileId)
    override val libraryWarning get() = catalog.warning
    override fun playlists() = catalog.playlists()
    override fun recommend() = catalog.recommend()
    override fun charts() = catalog.charts()
    override fun playlistTracks(id: Long) = if (id == CloudDrives.QQ_ID) {
        val account = NativeAccounts.get(source) ?: throw SourceAccessException("Log in to QQ Music to read your cloud drive.")
        QqCloud().tracks(account)
    } else QqArtists.albumTracksFor(id) ?: catalog.tracks(id)

    override val hasArtists get() = true
    override fun artist(id: String) = if (id.matches(Regex("[A-Za-z0-9]{1,64}"))) QqArtists.artist(id) else null
    override fun artistAlbums(id: String) = if (id.matches(Regex("[A-Za-z0-9]{1,64}"))) QqArtists.artistAlbums(id) else emptyList()
    override fun findArtist(name: String) = QqArtists.findArtist(name)

    // 红心先不做：往"我喜欢"（dirId 201）写，QQ 只认真 App 的设备身份，我们的登录会被拒（80105）
    override val canAddToPlaylist get() = NativeAccounts.get(source) != null
    override fun editablePlaylists() = catalog.editable()
    override fun addToPlaylist(playlist: NeteasePlaylist, track: NeteaseTrack): Boolean {
        if (track.source != source) throw SourceAccessException("This song is not from QQ Music.")
        return catalog.add(playlist, songIdOf(track))
    }

    /** 写歌单要数字歌曲 ID：解析时有就用，没有按 mid 查一次 */
    private fun songIdOf(track: NeteaseTrack): Long {
        if (track.sourceSongId > 0) return track.sourceSongId
        val data = cgi("music.pf_song_detail_svr", "get_song_detail_yqq", JsonObject().apply {
            addProperty("song_type", 0); addProperty("song_mid", track.nativeId)
        })
        val id = data.obj("track_info")?.number("id") ?: 0L
        if (id <= 0) throw SourceAccessException("QQ Music could not find this song. Please retry.")
        track.sourceSongId = id
        return id
    }

    internal fun profileId(account: NativeAccount): String {
        try {
            val profile = cgi("music.UserInfo.userInfoServer", "GetLoginUserInfo", JsonObject(), account)
            for (block in listOfNotNull(profile.obj("info"), profile.obj("info")?.obj("base"), profile)) {
                for (field in listOf("encrypt_uin", "encryptUin", "encryptedUin")) block.text(field).takeIf { it.isNotEmpty() }?.let { return it }
            }
        } catch (_: Exception) { /* Older account profiles can resolve their public ID via the homepage. */ }
        // The public c6 route resolves numeric IDs; c.y's older route requires a different web session.
        // No login cookies are necessary for this public metadata lookup.
        val data = SourceHttp.json(
            "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?format=json&cid=205360838&ct=20&cv=4747474&userid=${account.userId}", REFERER)
        return data.obj("data")?.obj("creator")?.text("encrypt_uin")?.takeIf { it.isNotEmpty() }
            ?: throw SourceAccessException("QQ Music profile is unavailable. Please retry or log in again.")
    }

    internal fun cgi(module: String, method: String, params: JsonObject, account: NativeAccount? = NativeAccounts.get(source)): JsonObject =
        QqApi(account).cgi(module, method, params)

    override fun search(query: String) = QqApi(NativeAccounts.get(source)).search(query, 0, 40).tracks

    internal fun parseSearch(response: JsonObject): List<NeteaseTrack> =
        response.obj("data")?.obj("song")?.array("list")?.mapNotNull { parseTrack(it.asJsonObject) } ?: emptyList()

    internal fun parseTrack(song: JsonObject) = QqApi.parseTrack(song)

    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? =
        QqApi(NativeAccounts.get(source)).audio(track)?.let { NeteaseApi.SongUrl(it.url, it.trialOnly) }

    private fun json(url: String): JsonObject {
        val account = NativeAccounts.get(source)
        return if (account == null) SourceHttp.json(url, REFERER) else AuthHttp.request(source, url, account.cookies).json()
    }

    override fun lyrics(track: NeteaseTrack): Lyrics? {
        try {
            val data = cgi("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", JsonObject().apply {
                addProperty("songMid", track.nativeId); addProperty("type", 1)
                for (key in listOf("lrc_t", "qrc_t", "roma", "roma_t", "trans_t")) addProperty(key, 0)
                addProperty("trans", 1)
                addProperty("crypt", 1); addProperty("qrc", 1)
            })
            val lines = QqLyrics.decode(data.text("lyric")).map { line ->
                me.sorihiko.dango.lyrics.LyricLine(line.timeMs, line.text, line.words?.map {
                    me.sorihiko.dango.lyrics.LyricWord(it.startMs, it.durMs, it.text)
                })
            }
            if (lines.isNotEmpty()) return Lyrics(LyricsProvider.withTranslation(lines, translation(data.text("trans"))), source.label)
        } catch (_: Exception) { /* Songs without QRC still use the ordinary line-timed lyric endpoint. */ }
        val root = json("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=${SourceHttp.enc(track.nativeId)}")
        val lines = LyricsProvider.parseLrc(root.text("lyric"))
        return if (lines.isEmpty()) null else Lyrics(LyricsProvider.withTranslation(lines, translation(root.text("trans"))), source.label)
    }

    /** QQ 的翻译是普通 LRC（PlayLyricInfo 里和 QRC 一样加了密），解不出来就当没有 */
    private fun translation(value: String): List<me.sorihiko.dango.lyrics.LyricLine> = try {
        if (value.isBlank()) emptyList() else LyricsProvider.parseLrc(QqLyrics.plaintext(value))
    } catch (_: Exception) {
        emptyList()
    }
}
