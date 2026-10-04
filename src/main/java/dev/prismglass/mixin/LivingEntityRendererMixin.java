package dev.prismglass.mixin;

import dev.prismglass.module.render.Unhide;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    /** Unhide: draw invisible entities normally, or through vanilla's translucent "teammate" path. */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL"))
    private void prism$unhide(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
        Unhide u = Unhide.active();
        if (u == null || !state.isInvisible || !u.applies(entity)) return;
        if (u.translucent.get()) state.isInvisibleToPlayer = false; // -> translucent render layer
        else state.isInvisible = false;                           // -> normal render
    }

    /** The translucent path's fixed 15% alpha (0x26FFFFFF) becomes Unhide's alpha. */
    @ModifyConstant(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        constant = @Constant(intValue = 654311423))
    private int prism$ghostAlpha(int original) {
        Unhide u = Unhide.active();
        return u != null && u.translucent.get() ? u.ghostColor() : original;
    }
}
