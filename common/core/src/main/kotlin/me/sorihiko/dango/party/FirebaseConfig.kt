package me.sorihiko.dango.party

import me.sorihiko.dango.DangoMusic

import java.io.InputStream
import java.util.Properties

/**
 * "一起听歌"用的 Firebase 项目配置。为了不把 Key 写进公开的源码，运行时按顺序读：
 *   1. .minecraft/config/dangomusic/firebase.properties（玩家自己填，方便换成自己的 Firebase 项目）
 *   2. jar 里的 /firebase.properties（构建时从 src/main/resources/firebase.properties 打进去，这个文件不上传 Git）
 * 都没有就留空，"一起听歌"页面会提示未配置，其它功能不受影响。
 *
 * 文件格式见仓库里的 firebase.properties.example：
 *   apiKey=AIzaSy...
 *   databaseUrl=https://xxx-default-rtdb.firebaseio.com
 *   relayUrl=https://party.example.com   （可选，一起听歌中转，见 relay/README.md）
 */
object FirebaseConfig {

    private val props: Properties by lazy {
        val p = Properties()
        val local = try {
            DangoMusic.instance.clientDir.resolve("firebase.properties").toFile()
        } catch (_: Throwable) {
            null
        }
        val stream: InputStream? = when {
            local != null && local.isFile -> local.inputStream()
            else -> FirebaseConfig::class.java.getResourceAsStream("/firebase.properties")
        }
        try {
            stream?.use { p.load(it) }
        } catch (_: Exception) {
        }
        p
    }

    /** apiKey，形如 "AIzaSy..." */
    val API_KEY: String get() = props.getProperty("apiKey", "").trim()

    /** 实时数据库地址，形如 "https://xxx-default-rtdb.firebaseio.com"（或 ...firebasedatabase.app） */
    val DATABASE_URL: String get() = props.getProperty("databaseUrl", "").trim()

    /** 一起听歌中转（Cloudflare Worker）地址；没填或格式不对时为空，表示直连 Firebase */
    val RELAY_URL: String get() = relayUrl(props.getProperty("relayUrl", ""))

    /** 只认 https（本机地址允许 http，方便测试）；去掉结尾的斜杠 */
    @JvmStatic
    fun relayUrl(raw: String): String {
        val s = raw.trim().trimEnd('/')
        val ok = Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?").matches(s) ||
            Regex("""http://(127\.0\.0\.1|localhost)(:[0-9]{1,5})?""").matches(s)
        return if (ok) s else ""
    }
}
