package me.sorihiko.dango.events

import me.sorihiko.dango.event.Event

data class WorldRenderEndEvent(
    val partialTicks: Float,
) : Event