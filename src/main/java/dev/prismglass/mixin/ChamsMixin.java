package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.render.Chams;
import dev.prismglass.util.ChamsRender;
import dev.prismglass.util.ChamsState;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chams + PopChams. Extraction tags the state with the Chams colour; submission draws the model as usual (tinted in
 * Tint mode) plus a coloured pass through walls. PopChams ghosts draw only that coloured pass and no layers.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ChamsMixin {
    @Shadow public abstract Identifier getTextureLocation(LivingEntityRenderState state);

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL"))
    private void prism$chamsExtract(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
        Chams chams = Prism.modules().get(Chams.class);
        ((ChamsState) state).prism$setChams(chams != null && chams.isEnabled() ? chams.colorFor(entity) : 0);
        ((ChamsState) state).prism$setGhost(false);
    }

    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
    private void prism$chamsSubmit(SubmitNodeCollector collector, Model<?> model, Object stateObj, PoseStack pose, RenderType type, int light, int overlay,
                                   int tint, UvMapping uv, int outline, Operation<Void> original) {
        // 26.3: the sprite argument became a UvMapping and the crumbling overlay argument is gone
        if (!(stateObj instanceof LivingEntityRenderState state) || ((ChamsState) state).prism$chams() == 0) {
            original.call(collector, model, stateObj, pose, type, light, overlay, tint, uv, outline);
            return;
        }
        int color = ((ChamsState) state).prism$chams();
        if (((ChamsState) state).prism$ghost()) {
            var pop = Prism.modules().get(dev.prismglass.module.render.PopChams.class);
            original.call(collector, model, stateObj, pose, ChamsRender.type(getTextureLocation(state), pop.throughWalls.get()), 0xF000F0, overlay, color, null, 0);
            return;
        }
        Chams chams = Prism.modules().get(Chams.class);
        original.call(collector, model, stateObj, pose, type, light, overlay, chams.tint() ? chams.tintOf(color) : tint, uv, outline);
        if (chams.walls()) {
            original.call(collector, model, stateObj, pose, ChamsRender.type(getTextureLocation(state), true), 0xF000F0, overlay, color, null, 0);
        }
    }

    /** PopChams ghosts: no armour, held items or capes. */
    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"))
    private boolean prism$ghostLayers(LivingEntityRenderer<?, ?, ?> self, LivingEntityRenderState state, Operation<Boolean> original) {
        return !((ChamsState) state).prism$ghost() && original.call(self, state);
    }
}
