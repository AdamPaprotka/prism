package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.prismglass.module.render.SnapAnims;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    /** SnapAnims: extract at the latest tick (no tweening between ticks), then grid-snap the state. */
    @WrapOperation(method = "extractEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState prism$snap(EntityRenderDispatcher dispatcher, Entity entity, float partialTick, Operation<EntityRenderState> original) {
        SnapAnims snap = SnapAnims.active(entity);
        if (snap == null) return original.call(dispatcher, entity, partialTick);
        EntityRenderState state = original.call(dispatcher, entity, 1f);
        snap.snap(state);
        return state;
    }
}
