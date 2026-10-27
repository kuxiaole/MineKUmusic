package me.sorihiko.dango.legacy

import me.sorihiko.dango.platform.KeyBindingLink
import me.sorihiko.dango.platform.PlayerAction
import me.sorihiko.dango.platform.PlayerKeyBinding
import me.sorihiko.dango.platform.savedPlayerKeys
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.fml.client.registry.ClientRegistry

object LegacyKeyBindings {
    private val mappings by lazy {
        PlayerAction.values().associateWith { KeyBinding(it.translationKey, it.defaultKey, "key.categories.dangomusic") }
    }
    private var registered = false
    private var links: List<KeyBindingLink>? = null

    @JvmStatic
    fun register() {
        if (registered) return
        mappings.values.forEach { ClientRegistry.registerKeyBinding(it) }
        registered = true
    }

    fun sync(bindings: List<PlayerKeyBinding>) {
        if (!registered) return
        val settings = mc.gameSettings ?: return
        var dirty = false
        if (links == null) {
            val saved = savedPlayerKeys(mc.mcDataDir.toPath().resolve("options.txt"))
            links = bindings.map { binding ->
                val mapping = mappings.getValue(binding.action)
                KeyBindingLink(binding, { mapping.keyCode }, { mapping.keyCode = it },
                    saved[binding.action.translationKey]?.toIntOrNull())
            }
            dirty = true
        }
        links!!.forEach { if (it.sync()) dirty = true }
        if (dirty) {
            KeyBinding.resetKeyBindingArrayAndHash()
            settings.saveOptions()
        }
    }
}
