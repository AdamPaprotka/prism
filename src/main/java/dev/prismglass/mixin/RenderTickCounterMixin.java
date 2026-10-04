package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.prismglass.Prism;
import dev.prismglass.module.world.Timer;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DeltaTracker.Timer.class)
public abstract class RenderTickCounterMixin {
    /** Shorter milliseconds-per-tick = more ticks per second. */
    @ModifyExpressionValue(method = "advanceGameTime(J)I", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/floats/FloatUnaryOperator;apply(F)F"))
    private float prism$timer(float msPerTick) {
        if (Prism.modules() == null) return msPerTick;
        Timer t = Prism.modules().get(Timer.class);
        return t != null && t.isEnabled() ? msPerTick / t.speed() : msPerTick;
    }
}
