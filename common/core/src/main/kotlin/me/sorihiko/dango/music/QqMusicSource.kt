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
    override fun playlistTracks(id: Long) = catalog.tracks(id)

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

    override fun lyrics(track: NeteaseTrack): Lyrics? {
        val lines = QqApi(NativeAccounts.get(source)).lyrics(track.nativeId).map { line ->
            me.sorihiko.dango.lyrics.LyricLine(line.timeMs, line.text, line.words?.map {
                me.sorihiko.dango.lyrics.LyricWord(it.startMs, it.durMs, it.text)
            })
        }
        return if (lines.isEmpty()) null else Lyrics(lines, source.label)
    }
}
