package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.After
import org.junit.BeforeClass
import org.junit.Assert.*
import org.junit.Test
import java.net.CookieManager

class KugouCatalogTest {
    companion object {
        @BeforeClass @JvmStatic fun initialize() = ServerMusicTest.initialize()
    }
    @After fun cleanup() { NativeAccounts.logout(MusicSource.KUGOU) }

    private fun load(missingSort: Boolean): List<String> {
        NativeAccounts.save(NativeAccount(MusicSource.KUGOU, "123456", "Fixture", "synthetic-test-token", "fixture-device", CookieManager()))
        val catalog = KugouCatalog(call = { path, _, body, _ ->
            when (path) {
                "/cloudlist.service/v8/get_all_list" -> JsonObject().apply {
                    addProperty("list_count", 1)
                    add("info", JsonArray().apply { add(JsonObject().apply {
                        addProperty("listid", 42); addProperty("name", "Fixture playlist"); addProperty("count", 4)
                    }) })
                }
                "/cloudlist.service/v4/get_list_all_file" -> JsonObject().apply {
                    addProperty("count", 4)
                    val page = body!!.get("page").asInt
                    add("info", JsonArray().apply {
                        for (id in if (page == 1) listOf(1, 2) else listOf(3, 4)) add(JsonObject().apply {
                            addProperty("hash", id.toString().padStart(32, '0'))
                            addProperty("songname", "Song $id")
                            if (!missingSort || id != 3) addProperty("sort", listOf(40, 10, 30, 20)[id - 1])
                        })
                    })
                }
                else -> error("Unexpected network request: $path")
            }
        })
        return catalog.tracks(catalog.playlists().single().id).map { it.name }
    }

    @Test fun personalPlaylistUsesAppOrderAcrossPages() {
        assertEquals(listOf("Song 2", "Song 4", "Song 3", "Song 1"), load(false))
    }

    @Test fun incompleteSortMetadataKeepsTheExistingNewestFirstFallback() {
        assertEquals(listOf("Song 4", "Song 3", "Song 2", "Song 1"), load(true))
    }
}
