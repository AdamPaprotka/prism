package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.render.NoRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void prism$fire(PoseStack matrices, MultiBufferSource consumers, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (NoRender.on(NoRender::fire)) ci.cancel();
    }

    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void prism$water(Minecraft client, PoseStack matrices, MultiBufferSource consumers, CallbackInfo ci) {
        if (NoRender.on(NoRender::liquid)) ci.cancel();
    }

    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void prism$wall(TextureAtlasSprite sprite, PoseStack matrices, MultiBufferSource consumers, CallbackInfo ci) {
        if (NoRender.on(NoRender::inWall)) ci.cancel();
    }
}
