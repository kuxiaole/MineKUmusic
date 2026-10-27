package me.sorihiko.dango.music

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 网易云里的一首歌（ID、时长、封面都是网易云自己的数据） */
class NeteaseTrack(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    val coverUrl: String,
) {
    companion object {
        /** 解析网易云接口返回的歌曲 JSON（新旧两种字段名都认：artists/ar、duration/dt、album/al） */
        fun parse(track: JsonObject): NeteaseTrack? {
            val id = track.get("id")?.let { if (it.isJsonNull) null else it.asString.toLongOrNull() } ?: return null
            val name = str(track, "name")
            val artistsEl: JsonElement? = track.get("artists") ?: track.get("ar")
            val artists = if (artistsEl != null && artistsEl.isJsonArray) {
                artistsEl.asJsonArray.mapNotNull { a ->
                    if (a.isJsonObject) str(a.asJsonObject, "name").takeIf { it.isNotEmpty() } else null
                }
            } else emptyList()
            val dur = (track.get("duration") ?: track.get("dt"))
                ?.let { if (it.isJsonNull) 0L else try { it.asLong } catch (_: Exception) { 0L } } ?: 0L
            val album = track.get("album") ?: track.get("al")
            val cover = if (album != null && album.isJsonObject) {
                str(album.asJsonObject, "picUrl").ifEmpty { str(album.asJsonObject, "cover") }
            } else ""
            return NeteaseTrack(id, name, artists, dur, cover)
        }

        private fun str(o: JsonObject, name: String): String {
            val e = o.get(name) ?: return ""
            return if (e.isJsonNull || !e.isJsonPrimitive) "" else e.asString
        }
    }
}
