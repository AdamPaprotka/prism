package dev.prismglass.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.gui.ClickGuiScreen;
import dev.prismglass.module.render.Fullbright;
import dev.prismglass.module.render.NoRender;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** Topmost layer: after the screen, toasts and debug overlay have all been extracted. */
    @Inject(method = "extractGui", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;applyCursor(Lcom/mojang/blaze3d/platform/Window;)V"))
    private void prism$hudOnTop(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo ci,
                                @Local GuiGraphicsExtractor graphics) {
        if (prism$hudUnderScreen()) return;
        Prism.renderHud(graphics, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    /** With the ClickGUI open the HUD goes right under it instead. */
    @Inject(method = "extractGui", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void prism$hudUnderClickGui(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo ci,
                                        @Local GuiGraphicsExtractor graphics) {
        if (prism$hudUnderScreen()) Prism.renderHud(graphics, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    /** Our own screens draw over the HUD (the HUD editor's outlines must sit on top of it). */
    private static boolean prism$hudUnderScreen() {
        var s = Minecraft.getInstance().screen;
        return s instanceof ClickGuiScreen || s instanceof dev.prismglass.gui.HudEditorScreen || s instanceof dev.prismglass.gui.ChangelogScreen
            || s instanceof dev.prismglass.gui.AboutScreen;
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void prism$hurtCam(CameraRenderState cameraState, PoseStack matrices, CallbackInfo ci) {
        if (NoRender.on(NoRender::hurtCam)) ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void prism$bob(CameraRenderState cameraState, PoseStack matrices, CallbackInfo ci) {
        if (NoRender.on(NoRender::bob)) ci.cancel();
    }

    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void prism$totem(ItemStack stack, CallbackInfo ci) {
        if (stack.is(Items.TOTEM_OF_UNDYING) && NoRender.on(NoRender::totem)) ci.cancel();
    }

    @Inject(method = "getNightVisionScale", at = @At("HEAD"), cancellable = true)
    private static void prism$nightVision(LivingEntity entity, float tickDelta, CallbackInfoReturnable<Float> cir) {
        Fullbright fb = Prism.modules().get(Fullbright.class);
        if (fb != null && fb.isEnabled()) cir.setReturnValue(1f);
    }
}
