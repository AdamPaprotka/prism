package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.render.PopChams;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PopChams: ghosts join the frame's entity list, so they're submitted (and lit, sorted) like real entities. */
@Mixin(LevelRenderer.class)
public abstract class PopChamsMixin {
    @Inject(method = "extractVisibleEntities", at = @At("TAIL"))
    private void prism$popGhosts(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
        PopChams pc = Prism.modules().get(PopChams.class);
        if (pc != null && pc.isEnabled()) pc.addGhosts(output.entityRenderStates);
    }
}
