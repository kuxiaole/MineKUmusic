package me.sorihiko.dango.modern

import com.mojang.blaze3d.platform.InputConstants
import me.sorihiko.dango.platform.KeyBindingLink
import me.sorihiko.dango.platform.PlayerAction
import me.sorihiko.dango.platform.PlayerKeyBinding
import me.sorihiko.dango.platform.savedPlayerKeys
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import java.util.function.Consumer

/**
 * 通过各加载器的正式注册入口加入 Controls；不改写 Options 的字段或重新加载设置。
 *
 * options.save() 会把所有按键（包括其他模组的）的当前值写回 options.txt，所以只在我们的按键真的变了、
 * 并且游戏已经加载完时才保存：有的加载器 / 模组组合（例如 NeoForge + Placebo）会在加载完成后
 * 才把其他模组的按键从 options.txt 读回来，这之前保存会把它们写成默认值（issue #18）。
 */
object ModernKeyBindings {
    private val mappings by lazy { PlayerAction.values().associateWith { McCompat.createPlayerKey(it) } }
    private var registered = false
    private var links: List<KeyBindingLink>? = null

    @JvmStatic
    fun register(register: Consumer<KeyMapping>) {
        if (registered) return
        mappings.values.forEach { register.accept(it) }
        registered = true
    }

    fun sync(bindings: List<PlayerKeyBinding>) {
        if (!registered) return
        val mc = Minecraft.getInstance()
        val options = mc.options ?: return
        // 加载画面还在：游戏（和其他模组的按键）还没加载完，先什么都不做
        if (!McCompat.gameLoaded(mc)) return
        var dirty = false
        if (links == null) {
            val saved = savedPlayerKeys(mc.gameDirectory.toPath().resolve("options.txt"))
            links = bindings.map { binding ->
                val mapping = mappings.getValue(binding.action)
                val before = mapping.saveString()
                val initial = saved[binding.action.translationKey]?.let { name ->
                    runCatching { McCompat.commonBindingKey(InputConstants.getKey(name.substringBefore(':'))) }.getOrNull()
                }
                KeyBindingLink(binding,
                    { McCompat.commonBindingKey(InputConstants.getKey(mapping.saveString())) },
                    { mapping.setKey(McCompat.gameBindingKey(it)) }, initial)
                    // 只有我们的按键和 options.txt 里的不一样时才需要保存
                    .also { if (mapping.saveString() != before) dirty = true }
            }
        }
        // 不用 any()，以免短路后漏掉其它同时修改的按键。
        links!!.forEach { if (it.sync()) dirty = true }
        if (dirty) {
            KeyMapping.resetMapping()
            options.save()
        }
    }
}
