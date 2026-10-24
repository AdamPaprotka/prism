package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.gui.HudLayer;
import dev.prismglass.module.render.Fullbright;
import dev.prismglass.module.render.NoRender;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3: the GUI is extracted by {@code Gui.extractRenderState} (called from GameRenderer.extract), the totem pop
 * moved to LocalPlayer/ScreenEffectRenderer (NoRender totem: InGameOverlayRendererMixin) and the night vision scale is
 * a static {@code nightVisionScale}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * Topmost layer: after the screen, toasts and debug overlay have all been extracted. Gui builds its extractor
     * locally, so the HUD gets its own on the same render state (it only adds strata on top).
     */
    @Inject(method = "extract", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", shift = At.Shift.AFTER))
    private void prism$hudOnTop(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (HudLayer.underScreen()) return; // ClickGUI & co.: the screen drew it under itself (HudLayer.under)
        Minecraft mc = Minecraft.getInstance();
        int x = (int) mc.mouseHandler.getScaledXPos(mc.getWindow()), y = (int) mc.mouseHandler.getScaledYPos(mc.getWindow());
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(mc, ((GameRenderer) (Object) this).gameRenderState().guiRenderState, x, y);
        Prism.renderHud(graphics, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void prism$hurtCam(CameraRenderState cameraState, PoseStack matrices, CallbackInfo ci) {
        if (NoRender.on(NoRender::hurtCam)) ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void prism$bob(CameraRenderState cameraState, PoseStack matrices, CallbackInfo ci) {
        if (NoRender.on(NoRender::bob)) ci.cancel();
    }

    @Inject(method = "nightVisionScale", at = @At("HEAD"), cancellable = true)
    private static void prism$nightVision(LivingEntity camera, float a, CallbackInfoReturnable<Float> cir) {
        Fullbright fb = Prism.modules().get(Fullbright.class);
        if (fb != null && fb.isEnabled()) cir.setReturnValue(1f);
    }
}
