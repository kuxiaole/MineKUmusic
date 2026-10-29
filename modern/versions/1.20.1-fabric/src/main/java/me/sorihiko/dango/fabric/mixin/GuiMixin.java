package me.sorihiko.dango.fabric.mixin;

import me.sorihiko.dango.modern.ModernDango;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版画完暗角之后：画 Song card、灵动岛、Now playing，这样暗角压不到它们，快捷栏照常盖在上面。
 * 1.20.1 的暗角只在"高品质"画质下画，所以不注入在暗角那一句后面，而是紧接着的、每次都会走到的
 * getDeltaFrameTime（之后才是望远镜、南瓜头等遮罩）。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getDeltaFrameTime()F", ordinal = 0))
    private void dangomusic$overlay(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        ModernDango.hudOverlay(graphics);
    }
}
