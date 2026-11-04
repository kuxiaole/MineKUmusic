package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 一起听歌的同步层。[ListenParty] 只认这个接口，不关心后面是 Firebase、中转还是别的服务。
 * 路径和数据布局沿用 Firebase 实时数据库的写法（"rooms/D-ABCDE/state"，JSON 树）。
 */
interface PartyTransport {

    /** 配置填好了没有 */
    val configured: Boolean

    /** 本机在同步服务里的固定身份 */
    val uid: String

    /** 最近一次请求的 HTTP 状态码（界面上显示错误原因用；-1 表示网络错误） */
    val lastStatus: Int

    /** 最近一次请求失败卡在哪一步，界面据此给出具体提示 */
    val lastFailure: Failure

    /** 现在经哪条线路连服务器（界面显示用） */
    val route: Route

    /** 确保已登录且令牌没过期；返回 false 表示连不上 */
    fun ensureAuth(): Boolean

    /** 校准本机和服务器的时差 */
    fun syncClock()

    /** 服务器时间（各人电脑时钟不一样，同步进度要用同一个时间） */
    fun serverNow(): Long

    /** 写入时由服务器替换成它的当前时间的占位值 */
    fun serverTimestamp(): JsonElement

    fun get(path: String): JsonElement?

    /** 读到的数据 + 版本号，配合 [putIfUnchanged] 做"没被别人改过才写" */
    fun getWithEtag(path: String): Snapshot?

    /** 只有路径上的数据还是 [etag] 那个版本时才写入；期间被别人改过会失败 */
    fun putIfUnchanged(path: String, value: JsonElement, etag: String): JsonElement?

    fun put(path: String, value: JsonElement): JsonElement?

    fun patch(path: String, value: JsonElement): JsonElement?

    fun delete(path: String): Boolean

    /**
     * 监听某个路径，变化连同数据一起推给 [onEvent]（在后台线程）。
     * 每次（重新）连上时先推一条 path 为 "/" 的 PUT，内容是整个路径当前的数据。断线自动重连。
     * 返回一个停止函数。
     */
    fun listen(path: String, onEvent: (PartyEvent) -> Unit): () -> Unit

    class Snapshot(val value: JsonElement?, val etag: String)

    enum class Failure {
        NONE,
        /** 连不上登录服务（Firebase 是 Google 的 *.googleapis.com） */
        AUTH_UNREACHABLE,
        /** 连不上数据库 */
        SERVER_UNREACHABLE,
        /** 手动填的代理连不上或地址不对 */
        PROXY_UNREACHABLE,
        /** 配了中转，但中转和直连都没连上 */
        RELAY_UNREACHABLE,
        /** 连上了，但服务器拒绝（HTTP 状态码不是 2xx） */
        REJECTED,
    }

    enum class Route {
        /** 没配中转，直连 */
        DIRECT,
        /** 经中转 */
        RELAY,
        /** 配了中转但暂时连不上，改为直连 */
        RELAY_DOWN,
    }
}

/**
 * 推送下来的一条变化：[path] 是相对监听路径的位置（"/" 表示整个路径），
 * PUT 把那个位置整个换成 [data]（null 表示删除），PATCH 把 [data] 里的每个子项合并进去。
 */
class PartyEvent(val type: Type, val path: String, val data: JsonElement?) {

    enum class Type { PUT, PATCH }

    companion object {
        /** 解析 Firebase SSE 的一条 put / patch：data 行形如 {"path":"/state","data":{...}} */
        @JvmStatic
        fun fromSse(event: String, data: String): PartyEvent? {
            val type = when (event) {
                "put" -> Type.PUT
                "patch" -> Type.PATCH
                else -> return null
            }
            return try {
                val o = JsonParser().parse(data) as? JsonObject ?: return null
                val path = o.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
                PartyEvent(type, path, o.get("data")?.takeUnless { it.isJsonNull })
            } catch (_: Exception) {
                null
            }
        }
    }
}
