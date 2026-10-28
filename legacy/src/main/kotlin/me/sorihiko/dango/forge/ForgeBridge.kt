package me.sorihiko.dango.forge

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.legacy.LegacyGl

import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent as ForgeTickEvent

/**
 * 把 Forge 的事件转成 mod 自己的事件：
 *   ClientTickEvent(START) → TickEvent（对应原来注入在 Minecraft.runTick 开头）
 *   RenderWorldLastEvent   → WorldRenderEndEvent（对应原来注入在 renderWorldPass 末尾、画手之前）
 */
class ForgeBridge {

    @SubscribeEvent
    fun onMenuDraw(e: net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Post) {
        if (me.sorihiko.dango.legacy.LegacyPlatform.inMainMenu) {
            me.sorihiko.dango.DangoClient.renderMainMenuNowPlaying()
        }
    }

    @SubscribeEvent
    fun onClientTick(e: ForgeTickEvent.ClientTickEvent) {
        if (e.phase != ForgeTickEvent.Phase.START) return
        try {
            LegacyGl.init()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music GL init failed: {}", t)
        }
        DangoMusic.callEvent(TickEvent)
    }

    @SubscribeEvent
    fun onRenderWorldLast(e: RenderWorldLastEvent) {
        DangoMusic.callEvent(WorldRenderEndEvent(e.partialTicks))
    }
}
