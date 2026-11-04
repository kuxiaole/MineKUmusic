package me.sorihiko.dango.party

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 本地保存一份监听路径的数据，按推送的 [PartyEvent] 原地更新，收到推送后不用再去读整个房间。
 * 推送线程写、别的线程读，所以读写都在同一把锁里做。
 */
class RoomMirror {

    private val lock = Any()
    private var root: JsonElement? = null

    fun apply(e: PartyEvent) = synchronized(lock) {
        when (e.type) {
            PartyEvent.Type.PUT -> root = set(root, segments(e.path), e.data)
            PartyEvent.Type.PATCH -> {
                val data = e.data as? JsonObject ?: return@synchronized
                val base = segments(e.path)
                // 子项的名字也可能是多级路径（"members/xxx/lastSeen"）
                for ((k, v) in data.entrySet()) root = set(root, base + segments(k), v.takeUnless { it.isJsonNull })
            }
        }
    }

    fun clear() = synchronized(lock) { root = null }

    /** 在锁里读当前数据（null 表示路径上什么都没有）。不要把对象带出 [block] 再用。 */
    fun <T> read(block: (JsonObject?) -> T): T = synchronized(lock) { block(root as? JsonObject) }

    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    /** 把 [node] 下 [path] 处换成 [value]，返回新的 node。和数据库一样，删空的对象也一起删掉。 */
    private fun set(node: JsonElement?, path: List<String>, value: JsonElement?): JsonElement? {
        if (path.isEmpty()) return value
        val obj = node as? JsonObject ?: JsonObject()
        val key = path[0]
        val child = set(obj.get(key), path.subList(1, path.size), value)
        if (child == null) obj.remove(key) else obj.add(key, child)
        return if (obj.entrySet().isEmpty()) null else obj
    }
}
