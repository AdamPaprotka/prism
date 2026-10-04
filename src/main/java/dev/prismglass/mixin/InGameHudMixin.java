package dev.prismglass.mixin;

import dev.prismglass.module.render.NoRender;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class InGameHudMixin {
    @Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void prism$overlay(GuiGraphicsExtractor ctx, Identifier texture, float opacity, CallbackInfo ci) {
        // pumpkin blur and powder snow are the only callers
        if (NoRender.on(NoRender::pumpkin)) ci.cancel();
    }

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void prism$portal(GuiGraphicsExtractor ctx, float strength, CallbackInfo ci) {
        if (NoRender.on(NoRender::portal)) ci.cancel();
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void prism$vignette(GuiGraphicsExtractor ctx, Entity entity, CallbackInfo ci) {
        if (NoRender.on(NoRender::vignette)) ci.cancel();
    }
}
