package me.sorihiko.dango.neoforge;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 入口：启动 mod，把 NeoForge 的事件转成 mod 自己的事件。
 * 只在客户端加载（dist = CLIENT），其它代码（画图、按键、界面）和 Fabric / Forge 版共用。
 */
@Mod(value = "dangomusic", dist = Dist.CLIENT)
public class DangoMusicNeoForge {

    public DangoMusicNeoForge(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> ModernKeyBindings.register(event::register));
        ModernDango.start();
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onClientTick);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForge::onMenuDraw);
    }

    private static void onMenuDraw(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        ModernDango.mainMenuRender(event.getGuiGraphics());
    }

    /** 每个游戏 tick 开头 */
    private static void onClientTick(ClientTickEvent.Pre event) {
        ModernDango.tick();
    }

    /** 世界画完（手和 HUD 之前） */
    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            ModernDango.worldRenderEnd();
        }
    }
}
