package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class NeteasePlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val coverUrl: String,
    /** 自己创建的（含"我喜欢的音乐"）还是收藏别人的 */
    val owned: Boolean,
)

/**
 * 网易云音乐接口（内置播放器用）。
 *
 * 登录：把网易云网页版登录后的 Cookie（至少包含 MUSIC_U=...）放进
 *   config/dangomusic/netease_cookie.txt
 * 只保存在本地，只在请求 music.163.com 时带上。
 */
object NeteaseApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val cookieFile: File get() = DangoMusic.instance.clientDir.resolve("netease_cookie.txt").toFile()

    @Volatile var nickname: String = ""
        private set
    @Volatile var userId: Long = 0L
        private set
    @Volatile var avatarUrl: String = ""
        private set

    /** 读 cookie 文件；只填了 MUSIC_U 的值也行 */
    private fun cookie(): String {
        val f = cookieFile
        if (!f.isFile) return ""
        var c = String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim()
        if (c.isEmpty()) return ""
        if (!c.contains("=")) c = "MUSIC_U=$c"
        return c
    }

    val loggedIn: Boolean get() = cookie().contains("MUSIC_U=")

    /** 扫码登录成功后保存 Cookie，并清掉缓存的账号信息 */
    fun saveCookie(cookie: String) {
        try {
            val f = cookieFile
            f.parentFile.mkdirs()
            f.writeText(cookie, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            DangoLogger.warn("Save NetEase cookie failed: {}", e.toString())
        }
        resetUser()
    }

    /** 退出登录：清空 Cookie */
    fun logout() {
        saveCookie("")
    }

    fun resetUser() {
        userId = 0L
        nickname = ""
        avatarUrl = ""
    }

    /** 第一次运行时创建一个带说明的空 cookie 文件，方便用户找到位置 */
    fun ensureCookieFile() {
        val f = cookieFile
        if (f.exists()) return
        try {
            f.parentFile.mkdirs()
            f.writeText("", StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 账号

    /** 拿到当前用户 ID：优先用 cookie 查账号；没登录时退回读网易云客户端本地记录里的 uid */
    fun resolveUser(): Long {
        if (userId != 0L) return userId
        if (loggedIn) {
            try {
                val o = getJson("https://music.163.com/api/nuser/account/get")
                val profile = o?.get("profile") as? JsonObject
                val account = o?.get("account") as? JsonObject
                val id = profile?.get("userId")?.asLong ?: account?.get("id")?.asLong ?: 0L
                if (id != 0L) {
                    userId = id
                    nickname = profile?.get("nickname")?.asString ?: ""
                    avatarUrl = profile?.str("avatarUrl") ?: ""
                    return id
                }
            } catch (e: Exception) {
                DangoLogger.warn("NetEase account lookup failed: {}", e.toString())
            }
        }
        // 网易云客户端本地记录（不需要登录 cookie）
        try {
            val f = File(System.getenv("LOCALAPPDATA") ?: "", "NetEase\\CloudMusic\\webdata\\file\\recentListen")
            if (f.isFile) {
                val o = JsonParser().parse(String(f.readBytes(), StandardCharsets.UTF_8))
                if (o.isJsonObject) {
                    val id = o.asJsonObject.get("uid")?.asString?.toLongOrNull() ?: 0L
                    if (id != 0L) userId = id
                }
            }
        } catch (_: Exception) {
        }
        return userId
    }

    // ---------------------------------------------------------------- 歌单

    fun userPlaylists(): List<NeteasePlaylist> {
        val uid = resolveUser()
        if (uid == 0L) return emptyList()
        val o = getJson("https://music.163.com/api/user/playlist/?uid=$uid&limit=1000&offset=0") ?: return emptyList()
        val arr = o.getAsJsonArray("playlist") ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val p = el.asJsonObject
            val creator = p.get("creator") as? JsonObject
            NeteasePlaylist(
                id = p.get("id")?.asLong ?: return@mapNotNull null,
                name = p.get("name")?.asString ?: "",
                trackCount = p.get("trackCount")?.asInt ?: 0,
                coverUrl = p.get("coverImgUrl")?.asString ?: "",
                owned = creator?.get("userId")?.asLong == uid,
            )
        }
    }

    /** 歌单里的全部歌曲：详情接口给出全部 ID，前一部分直接带歌曲信息，剩下的分批补齐 */
    fun playlistTracks(playlistId: Long): List<NeteaseTrack> {
        val o = getJson("https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=1000&s=0") ?: return emptyList()
        val pl = o.get("playlist") as? JsonObject ?: return emptyList()
        val known = LinkedHashMap<Long, NeteaseTrack>()
        pl.getAsJsonArray("tracks")?.forEach { el ->
            if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject)?.let { known[it.id] = it }
        }
        val ids = pl.getAsJsonArray("trackIds")?.mapNotNull { el ->
            if (el.isJsonObject) el.asJsonObject.get("id")?.asLong else null
        } ?: known.keys.toList()

        val missing = ids.filter { it !in known }
        TrackLoadProgress.report(ids.size - missing.size, ids.size)
        for (batch in missing.chunked(200)) {
            try {
                val res = getJson("https://music.163.com/api/song/detail?ids=" + enc(batch.joinToString(",", "[", "]")))
                res?.getAsJsonArray("songs")?.forEach { el ->
                    if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject)?.let { known[it.id] = it }
                }
            } catch (_: Exception) {
            }
            TrackLoadProgress.report(ids.count { it in known }, ids.size)
        }
        return ids.mapNotNull { known[it] }
    }

    /** 首页推荐歌单（不需要登录） */
    fun recommendPlaylists(): List<NeteasePlaylist> {
        val o = getJson("https://music.163.com/api/personalized/playlist?limit=30") ?: return emptyList()
        val arr = o.getAsJsonArray("result") ?: return emptyList()
        return arr.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            NeteasePlaylist(
                id = p.get("id")?.asLong ?: return@mapNotNull null,
                name = p.str("name"),
                trackCount = p.get("trackCount")?.asInt ?: 0,
                coverUrl = p.str("picUrl"),
                owned = false,
            )
        }
    }

    /** 排行榜（飙升榜、新歌榜、热歌榜……） */
    fun toplists(): List<NeteasePlaylist> {
        val o = getJson("https://music.163.com/api/toplist") ?: return emptyList()
        val arr = o.getAsJsonArray("list") ?: return emptyList()
        return arr.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            NeteasePlaylist(
                id = p.get("id")?.asLong ?: return@mapNotNull null,
                name = p.str("name"),
                trackCount = p.get("trackCount")?.asInt ?: 0,
                coverUrl = p.str("coverImgUrl"),
                owned = false,
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    // ---------------------------------------------------------------- 搜索

    /** 上一次搜索失败的原因（界面上显示）；成功或只是没结果时为空 */
    @Volatile var lastSearchError: String = ""
        private set

    /** 带登录信息的 cookie 头（weapi 请求用） */
    private fun cookieHeader(): String {
        val c = cookie()
        return if (c.isEmpty()) "os=pc; appver=2.10.13" else "$c; os=pc; appver=2.10.13"
    }

    private fun songsOf(o: JsonObject?): List<NeteaseTrack>? {
        val result = o?.get("result") as? JsonObject ?: return null
        val arr = result.getAsJsonArray("songs") ?: return emptyList()
        return arr.mapNotNull { el -> if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject) else null }
    }

    private fun codeOf(o: JsonObject?): String =
        o?.get("code")?.takeIf { !it.isJsonNull }?.asString ?: "no response"

    /**
     * 搜歌。网易云经常限制某个接口，所以依次试：
     *   1. cloudsearch（直接带封面、时长）
     *   2. weapi 加密版 cloudsearch（和网页版一样的请求方式，最不容易被拦）
     *   3. 老的网页搜索 + 详情接口补全
     */
    fun search(keyword: String, limit: Int = 50): List<NeteaseTrack> {
        if (keyword.isBlank()) return emptyList()
        val q = keyword.trim()
        val errors = mutableListOf<String>()

        // 1. cloudsearch
        try {
            val o = getJson("https://music.163.com/api/cloudsearch/pc?s=" + enc(q) + "&type=1&offset=0&limit=$limit")
            val songs = songsOf(o)
            if (!songs.isNullOrEmpty()) return done(songs)
            if (songs == null) errors += "cloudsearch " + codeOf(o)
        } catch (e: Exception) {
            errors += "cloudsearch " + e.javaClass.simpleName
        }

        // 2. weapi cloudsearch
        try {
            val body = JsonObject().apply {
                addProperty("s", q)
                addProperty("type", "1")
                addProperty("limit", limit.toString())
                addProperty("offset", "0")
                addProperty("total", "true")
                addProperty("csrf_token", "")
            }.toString()
            val res = NeteaseLogin.weapi("https://music.163.com/weapi/cloudsearch/get/web", body, cookieHeader())
            val root = res?.first?.let { JsonParser().parse(it) }
            val o = if (root != null && root.isJsonObject) root.asJsonObject else null
            val songs = songsOf(o)
            if (!songs.isNullOrEmpty()) return done(songs)
            if (songs == null) errors += "weapi " + codeOf(o)
        } catch (e: Exception) {
            errors += "weapi " + e.javaClass.simpleName
        }

        // 3. 老接口：先搜 ID，再用详情补全封面、时长
        try {
            val o = getJson("https://music.163.com/api/search/get/web?s=" + enc(q) + "&type=1&offset=0&limit=$limit")
            val basic = songsOf(o)
            if (basic == null) {
                errors += "web " + codeOf(o)
            } else if (basic.isNotEmpty()) {
                val detailed = HashMap<Long, NeteaseTrack>()
                try {
                    val res = getJson("https://music.163.com/api/song/detail?ids=" + enc(basic.joinToString(",", "[", "]") { it.id.toString() }))
                    res?.getAsJsonArray("songs")?.forEach { el ->
                        if (el.isJsonObject) NeteaseTrack.parse(el.asJsonObject)?.let { detailed[it.id] = it }
                    }
                } catch (_: Exception) {
                }
                return done(basic.map { t -> detailed[t.id]?.takeIf { it.coverUrl.isNotEmpty() } ?: t })
            }
        } catch (e: Exception) {
            errors += "web " + e.javaClass.simpleName
        }

        // 三个接口都没拿到结果：都正常返回只是没歌 → 没有结果；否则显示失败原因
        lastSearchError = errors.joinToString(", ")
        if (errors.isNotEmpty()) DangoLogger.warn("NetEase search failed: {}", lastSearchError)
        return emptyList()
    }

    private fun done(songs: List<NeteaseTrack>): List<NeteaseTrack> {
        lastSearchError = ""
        return songs
    }

    // ---------------------------------------------------------------- 播放地址

    class SongUrl(val url: String, val trialOnly: Boolean)

    /**
     * 播放地址。登录后能拿到账号有权限的完整音频；拿不到时退回网易云外链（只对免费歌曲有效）。
     * 只要 mp3（内置解码器只支持 mp3）。
     */
    fun songUrl(id: Long): SongUrl? {
        try {
            val o = getJson("https://music.163.com/api/song/enhance/player/url?ids=" + enc("[$id]") + "&br=192000")
            val d = o?.getAsJsonArray("data")?.firstOrNull() as? JsonObject
            val url = d?.get("url")?.let { if (it.isJsonNull) null else it.asString }
            val type = d?.get("type")?.let { if (it.isJsonNull) "" else it.asString.lowercase() } ?: ""
            val trial = d?.get("freeTrialInfo")?.let { !it.isJsonNull } ?: false
            if (!url.isNullOrEmpty() && (type.isEmpty() || type == "mp3")) return SongUrl(url, trial)
        } catch (e: Exception) {
            DangoLogger.warn("NetEase song url failed: {}", e.toString())
        }
        return SongUrl("https://music.163.com/song/media/outer/url?id=$id.mp3", false)
    }

    // ---------------------------------------------------------------- HTTP

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): JsonObject? {
        val bytes = request(url, accept = "application/json") ?: return null
        val root = JsonParser().parse(String(bytes, StandardCharsets.UTF_8))
        return if (root.isJsonObject) root.asJsonObject else null
    }

    /** 下载（跟随重定向，包括 http↔https）；只对 music.163.com 带 cookie */
    fun request(url: String, accept: String = "*/*", maxBytes: Int = 64 * 1024 * 1024): ByteArray? {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", accept)
                if (URL(current).host.endsWith("music.163.com")) {
                    conn.setRequestProperty("Referer", "https://music.163.com/")
                    val c = cookie()
                    conn.setRequestProperty("Cookie", if (c.isEmpty()) "os=pc; appver=2.10.13" else "$c; os=pc; appver=2.10.13")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) return null
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}
