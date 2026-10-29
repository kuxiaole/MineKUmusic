package me.sorihiko.dango.neoforge.mixin;

import me.sorihiko.dango.modern.ModernDango;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 开始画界面之前调用 ModernDango.beforeGui()（26.x 的界面是先收集再画，我们的界面要在这时画好）。
 * NeoForge 在这里没有事件，只能挂进原版。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V")
    )
    private void dangomusic$beforeGui(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        ModernDango.beforeGui();
    }
}
