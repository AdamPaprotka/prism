package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.render.NoRender;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender screen overlays. 26.3: renderFire/renderWater/renderTex became the submit-based submit* methods. */
@Mixin(ScreenEffectRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void prism$fire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (NoRender.on(NoRender::fire)) ci.cancel();
    }

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void prism$water(PlayerRenderState.WaterOverlay waterOverlay, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
        if (NoRender.on(NoRender::liquid)) ci.cancel();
    }

    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    private static void prism$wall(Identifier atlasLocation, float u0, float v0, float u1, float v1, PoseStack poseStack, SubmitNodeCollector collector,
                                   int color, CallbackInfo ci) {
        if (NoRender.on(NoRender::inWall)) ci.cancel();
    }

    /**
     * Totem pop animation. 26.3 moved displayItemActivation from GameRenderer to LocalPlayer and draws the animation
     * here from the extracted state, so the totem case is dropped at draw time instead of when it starts.
     */
    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void prism$totem(PlayerRenderState playerRenderState, PoseStack poseStack, float partialTicks, SubmitNodeCollector collector, CallbackInfo ci) {
        PlayerRenderState.ItemActivationRenderState activation = playerRenderState.itemActivation;
        if (activation != null && activation.item.is(Items.TOTEM_OF_UNDYING) && NoRender.on(NoRender::totem)) ci.cancel();
    }
}
