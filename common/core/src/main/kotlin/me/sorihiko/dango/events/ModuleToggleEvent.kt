package me.sorihiko.dango.events

import me.sorihiko.dango.event.Event
import me.sorihiko.dango.module.Module

abstract class ModuleToggleEvent(
    val module: Module,
) : Event {
    class Enable(module: Module) : ModuleToggleEvent(module)
    class Disable(module: Module) : ModuleToggleEvent(module)
}