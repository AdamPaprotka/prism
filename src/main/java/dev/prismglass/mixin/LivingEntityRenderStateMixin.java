package dev.prismglass.mixin;

import dev.prismglass.util.ChamsState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Chams / PopChams data carried from extraction to submission. */
@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements ChamsState {
    @Unique private int prism$chamsColor;
    @Unique private boolean prism$isGhost;

    @Override public int prism$chams() { return prism$chamsColor; }
    @Override public void prism$setChams(int argb) { prism$chamsColor = argb; }
    @Override public boolean prism$ghost() { return prism$isGhost; }
    @Override public void prism$setGhost(boolean ghost) { prism$isGhost = ghost; }
}
