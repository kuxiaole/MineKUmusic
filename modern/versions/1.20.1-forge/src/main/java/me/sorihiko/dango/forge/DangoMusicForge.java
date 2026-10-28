package me.sorihiko.dango.forge;

import me.sorihiko.dango.modern.ModernDango;
import me.sorihiko.dango.modern.ModernKeyBindings;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 入口：启动 mod，把 Forge 的事件转成 mod 自己的事件。
 * 其它代码（画图、按键、界面）和 Fabric 版共用。
 */
@Mod("dangomusic")
public class DangoMusicForge {

    public DangoMusicForge() {
        // 纯客户端 mod：在服务器上什么都不做
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Client.init();
        }
    }

    /** 单独放一个类：服务器上不会加载到这些客户端类 */
    private static final class Client {
        static void init() {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(Client::onRegisterKeys);
            ModernDango.start();
            MinecraftForge.EVENT_BUS.addListener(Client::onClientTick);
            MinecraftForge.EVENT_BUS.addListener(Client::onRenderLevel);
            MinecraftForge.EVENT_BUS.addListener(Client::onMenuDraw);
        }

        private static void onMenuDraw(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
            ModernDango.mainMenuRender(event.getGuiGraphics());
        }
        static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            ModernKeyBindings.register(event::register);
        }

        /** 每个游戏 tick 开头 */
        static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) ModernDango.tick();
        }

        /** LevelRenderer 返回后（光影合成之后、手和 HUD 之前），对应 Fabric 的 GameRenderer 注入点 */
        static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                ModernDango.worldRenderEnd();
            }
        }
    }
}
