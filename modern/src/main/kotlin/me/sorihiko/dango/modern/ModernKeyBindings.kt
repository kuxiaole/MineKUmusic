package me.sorihiko.dango.modern

import com.mojang.blaze3d.platform.InputConstants
import me.sorihiko.dango.platform.KeyBindingLink
import me.sorihiko.dango.platform.PlayerAction
import me.sorihiko.dango.platform.PlayerKeyBinding
import me.sorihiko.dango.platform.savedPlayerKeys
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import java.util.function.Consumer

/** 通过各加载器的正式注册入口加入 Controls；不改写 Options 的字段或重新加载设置。 */
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
        var dirty = false
        if (links == null) {
            val saved = savedPlayerKeys(mc.gameDirectory.toPath().resolve("options.txt"))
            links = bindings.map { binding ->
                val mapping = mappings.getValue(binding.action)
                val initial = saved[binding.action.translationKey]?.let { name ->
                    runCatching { McCompat.commonBindingKey(InputConstants.getKey(name.substringBefore(':'))) }.getOrNull()
                }
                KeyBindingLink(binding,
                    { McCompat.commonBindingKey(InputConstants.getKey(mapping.saveString())) },
                    { mapping.setKey(McCompat.gameBindingKey(it)) }, initial)
            }
            dirty = true
        }
        // 不用 any()，以免短路后漏掉其它同时修改的按键。
        links!!.forEach { if (it.sync()) dirty = true }
        if (dirty) {
            KeyMapping.resetMapping()
            options.save()
        }
    }
}
