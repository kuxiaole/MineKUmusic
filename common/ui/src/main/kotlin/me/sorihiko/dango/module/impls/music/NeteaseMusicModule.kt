package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.ServerMusic
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.platform.PlayerAction
import me.sorihiko.dango.platform.PlayerKeyBinding
import me.sorihiko.dango.update.UpdateChecker

/**
 * 内置网易云播放器。按 Open key（默认 M）打开播放器界面，播放自己的网易云歌单。
 * 登录：把网易云网页版的 MUSIC_U Cookie 填进 config/dangomusic/netease_cookie.txt
 * 开着 Lyrics 模块时，会优先用这里的播放进度、歌曲 ID 和封面。
 */
@ModuleMeta(Category.MUSIC, "NeteaseMusic")
class NeteaseMusicModule : Module() {

    @ModuleValue("Music source", "Select a music provider")
    private val musicSource = IntValue(0, 2, 0)

    fun selectSource(source: MusicSource) {
        musicSource.setValue(source.ordinal)
        MusicSources.selected = source
    }

    @ModuleValue("Volume", "Playback volume")
    private val volume = FloatValue(0f, 1f, 0.2f, 2)

    @ModuleValue("Language", "Choose the player interface language")
    // -1：尚未根据游戏语言初始化。早期 Fabric 入口处 Options 可能仍为空。
    private val language = IntValue(-1, 1, -1)

    @ModuleValue("Reduced channel motion", "Fade channel tabs without sliding")
    val reducedChannelMotion = BooleanValue(false)

    fun applyLanguage() {
        if (language.value < 0) {
            val gameLanguage = DangoMusic.instance.platform.languageCode ?: return
            language.setValue(I18n.defaultLanguage(gameLanguage))
        }
        I18n.language = language.value
    }

    @ModuleValue("Shuffle", "Play songs in random order")
    private val shuffle = BooleanValue(false)

    @ModuleValue("Repeat one", "Repeat the current song when it finishes")
    private val repeatOne = BooleanValue(false)

    @ModuleValue("Open key", "Key that opens the player")
    val openKey = IntValue(-100, 255, Keys.M)

    // 其它快捷键：默认都不绑定（0 = 无），在播放器设置里自己设
    @ModuleValue("Play / Pause", "Hotkey to play or pause")
    private val keyPause = IntValue(-100, 255, 0)

    @ModuleValue("Previous", "Hotkey for the previous song")
    private val keyPrev = IntValue(-100, 255, 0)

    @ModuleValue("Next", "Hotkey for the next song")
    private val keyNext = IntValue(-100, 255, 0)

    @ModuleValue("Volume up", "Hotkey to raise the volume by 5%")
    private val keyVolUp = IntValue(-100, 255, 0)

    @ModuleValue("Volume down", "Hotkey to lower the volume by 5%")
    private val keyVolDown = IntValue(-100, 255, 0)

    @ModuleValue("Toggle lyrics", "Hotkey to turn lyrics on or off")
    private val keyLyrics = IntValue(-100, 255, 0)

    @ModuleValue("Check for updates", "Look for a newer release on GitHub once per launch and remind you")
    val checkUpdates = BooleanValue(true)

    private val keyBindings by lazy {
        listOf(
            PlayerKeyBinding(PlayerAction.OPEN, openKey), PlayerKeyBinding(PlayerAction.PAUSE, keyPause),
            PlayerKeyBinding(PlayerAction.PREVIOUS, keyPrev), PlayerKeyBinding(PlayerAction.NEXT, keyNext),
            PlayerKeyBinding(PlayerAction.VOLUME_UP, keyVolUp), PlayerKeyBinding(PlayerAction.VOLUME_DOWN, keyVolDown),
            PlayerKeyBinding(PlayerAction.LYRICS, keyLyrics),
        )
    }

    fun resetKeyBinding(value: IntValue) {
        val binding = keyBindings.firstOrNull { it.value === value } ?: return
        value.setValue(binding.action.defaultKey)
    }

    private var wasDown = false

    /** 界面用打开键关闭时，消费这次按下，防止同一 tick 又从游戏里打开。 */
    fun consumeOpenKey() { wasDown = true }
    /** 每个快捷键上一刻是否按着（只在按下的瞬间触发一次） */
    private val hotkeyDown = HashMap<IntValue, Boolean>()

    private val hotkeys: List<Pair<IntValue, () -> Unit>> by lazy {
        listOf(
            keyPause to { ServerMusic.togglePause() },
            keyPrev to { ServerMusic.previous() },
            keyNext to { ServerMusic.next() },
            keyVolUp to { changeVolume(0.05f) },
            keyVolDown to { changeVolume(-0.05f) },
            keyLyrics to { lyrics()?.toggleLyrics() },
        )
    }

    override fun onEnable() {
        NeteaseApi.ensureCookieFile()
    }

    override fun onDisable() {
        if (ServerMusic.joined) ServerMusic.leave()
        MusicPlayer.stop()
    }

    fun setVolume(v: Float) {
        volume.setValue(v)
    }

    private fun changeVolume(d: Float) {
        volume.setValue((volume.value + d).coerceIn(0f, 1f))
        MusicPlayer.volume = volume.value
    }

    fun toggleShuffle() {
        if (ServerMusic.joined) return
        shuffle.toggle()
        MusicPlayer.shuffle = shuffle.get()
    }

    fun toggleRepeatOne() {
        if (ServerMusic.joined) return
        repeatOne.toggle()
        MusicPlayer.repeatOne = repeatOne.get()
    }

    /** 播放器界面里按键时调用：是某个快捷键就执行并返回 true */
    fun handleHotkey(keyCode: Int): Boolean {
        if (keyCode == Keys.NONE) return false
        val hit = hotkeys.firstOrNull { it.first.value == keyCode } ?: return false
        hit.second()
        return true
    }

    private fun lyrics(): LyricsModule? = try {
        DangoMusic.instance.moduleManager.get(LyricsModule::class.java)
    } catch (_: Exception) {
        null
    }

    private var tickCount = 0

    @EventHandler
    fun onTick(e: TickEvent) {
        // 即使播放器被关闭，也要初始化并保存界面语言。
        applyLanguage()
        MusicSources.selected = MusicSource.values()[musicSource.value]
        DangoMusic.instance.platform.syncKeyBindings(keyBindings)
        UpdateChecker.tick(checkUpdates.get())
        if (!enabled) return
        // 歌词模块一直开着（显示与否由 3D lyrics 控制）；等配置读回之后再检查
        if (++tickCount > 5) lyrics()?.let { if (!it.enabled) it.enabled = true }
        MusicPlayer.volume = volume.value
        MusicPlayer.shuffle = shuffle.get()

        MusicPlayer.repeatOne = repeatOne.get()

        // 打开键额外支持主菜单；其他播放快捷键仍只在世界内响应。
        val platform = DangoMusic.instance.platform
        val inGame = platform.inGame
        val down = try { platform.isKeyDown(openKey.value) } catch (_: Exception) { false }
        val open = down && !wasDown && (inGame || platform.inMainMenu)
        wasDown = down
        if (open || inGame && (ServerMusic.pendingRequest != null || ServerMusic.serverLogin != null)) platform.openMusicScreen()

        for ((key, action) in hotkeys) {
            val code = key.value
            val now = code != Keys.NONE && try { platform.isKeyDown(code) } catch (_: Exception) { false }
            if (now && hotkeyDown[key] != true && inGame) action()
            hotkeyDown[key] = now
        }
    }
}
