package me.sorihiko.dango.module

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.Listener
import me.sorihiko.dango.events.ModuleToggleEvent

abstract class Module : Listener {
    val category = javaClass.getAnnotation(ModuleMeta::class.java).category
    val name = javaClass.getAnnotation(ModuleMeta::class.java).name
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                DangoMusic.callEvent(ModuleToggleEvent.Enable(this))
                onEnable()
            }else {
                onDisable()
                DangoMusic.callEvent(ModuleToggleEvent.Disable(this))
            }
        }

    open fun init() {}
    protected open fun onEnable() {}
    protected open fun onDisable() {}
}