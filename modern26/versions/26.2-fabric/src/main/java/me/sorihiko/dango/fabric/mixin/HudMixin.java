package me.sorihiko.dango.fabric.mixin;

import me.sorihiko.dango.modern.ModernDango;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版收集 HUD 时，暗角（和南瓜头、望远镜等镜头遮罩）收集完之后：把 Song card、灵动岛、Now playing 那一层交给原版，
 * 排在暗角后面（暗角压不到它）、快捷栏前面。选 Vulkan 时交的是整个世界那一层（含歌词）。
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractCameraOverlays", at = @At("TAIL"))
    private void dangomusic$overlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ModernDango.hudOverlay(graphics);
    }
}
