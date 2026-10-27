package me.sorihiko.dango.musicui

import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.music.NeteaseTrack

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal enum class Page { HOME, CHARTS, PLAYLIST, SEARCH, QUEUE, PARTY, SETTINGS }

/** 关掉播放器再打开时还保留的界面状态 */
internal object ScreenState {
    var page = Page.HOME
    /** 设置页：0 = Lyrics，1 = Music & HUD；以及从哪一页点进来的 */
    var settingsTab = 0
    var settingsBack = Page.HOME
    var backPage: Page? = null
    var opened: NeteasePlaylist? = null
    /** 歌单页显示方式：false = 列表，true = 封面墙 */
    var coverView = false
    var lastQuery = ""

    // 液态玻璃画布（和菜单同一套），第一次打开播放器时创建
    var context: GuiCanvas? = null
}

/**
 * 从网易云读来的数据：我的歌单、歌单里的歌、推荐、排行榜、搜索结果。
 * 都在后台线程读，读完直接替换，界面每帧读最新的。
 */
internal object Library {
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "Dango-MusicUI").apply { isDaemon = true } }

    @Volatile var playlists: List<NeteasePlaylist> = emptyList()
        private set
    @Volatile var playlistsLoading = false
        private set
    /** 底部提示（读取失败等），空就不显示 */
    @Volatile var status = ""
        private set
    private val tracksCache = ConcurrentHashMap<Long, List<NeteaseTrack>>()
    private val tracksLoading = ConcurrentHashMap.newKeySet<Long>()

    @Volatile var recommend: List<NeteasePlaylist>? = null
        private set
    @Volatile var recommendLoading = false
        private set
    @Volatile var charts: List<NeteasePlaylist>? = null
        private set
    @Volatile var chartsLoading = false
        private set

    @Volatile var searchResults: List<NeteaseTrack>? = null
        private set
    @Volatile var searching = false
        private set

    /** 上次从网易云读歌单的时间：打开播放器时超过 [STALE_MS] 就在后台自动刷新 */
    private var lastLoadAt = 0L
    private const val STALE_MS = 5 * 60_000L

    val stale: Boolean get() = System.currentTimeMillis() - lastLoadAt > STALE_MS

    fun tracks(playlistId: Long): List<NeteaseTrack>? = tracksCache[playlistId]

    fun reloadPlaylists() {
        if (playlistsLoading) return
        playlistsLoading = true
        lastLoadAt = System.currentTimeMillis()
        status = "正在读取歌单…"
        worker.execute {
            try {
                NeteaseApi.ensureCookieFile()
                val list = NeteaseApi.userPlaylists()
                playlists = list
                status = if (list.isEmpty()) "没有读到歌单（检查网络或登录）" else ""
            } catch (e: Exception) {
                status = "读取歌单失败"
            } finally {
                playlistsLoading = false
            }
        }
    }

    fun loadRecommend() {
        if (recommend != null || recommendLoading) return
        recommendLoading = true
        worker.execute {
            recommend = try { NeteaseApi.recommendPlaylists() } catch (_: Exception) { emptyList() }
            recommendLoading = false
        }
    }

    fun loadCharts() {
        if (charts != null || chartsLoading) return
        chartsLoading = true
        worker.execute {
            charts = try { NeteaseApi.toplists() } catch (_: Exception) { emptyList() }
            chartsLoading = false
        }
    }

    /** 读一个歌单里的歌（已经读过或正在读就跳过） */
    fun loadTracks(playlistId: Long) {
        if (tracksCache.containsKey(playlistId) || !tracksLoading.add(playlistId)) return
        worker.execute {
            try {
                tracksCache[playlistId] = NeteaseApi.playlistTracks(playlistId)
            } catch (_: Exception) {
                tracksCache[playlistId] = emptyList()
            } finally {
                tracksLoading.remove(playlistId)
            }
        }
    }

    fun search(q: String) {
        searching = true
        searchResults = null
        worker.execute {
            try {
                searchResults = NeteaseApi.search(q)
            } catch (_: Exception) {
                searchResults = emptyList()
            } finally {
                searching = false
            }
        }
    }

    /**
     * 悄悄刷新：后台重新读歌单列表和已经打开过的歌单里的歌，读到了再替换，
     * 界面上不会出现"加载中"（游戏开着时在手机上新建歌单 / 加歌，下次打开播放器就能看到）
     */
    fun softRefresh() {
        lastLoadAt = System.currentTimeMillis()
        worker.execute {
            try {
                val list = NeteaseApi.userPlaylists()
                if (list.isNotEmpty()) playlists = list
            } catch (_: Exception) {
            }
            for (id in tracksCache.keys.toList()) {
                try {
                    val tracks = NeteaseApi.playlistTracks(id)
                    if (tracks.isNotEmpty()) tracksCache[id] = tracks
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 登录 / 退出后全部重新读 */
    fun refreshAll() {
        playlists = emptyList()
        tracksCache.clear()
        recommend = null
        charts = null
        reloadPlaylists()
        loadRecommend()
        if (ScreenState.page == Page.CHARTS) loadCharts()
    }
}
