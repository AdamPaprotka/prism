package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.prismglass.Prism;
import dev.prismglass.module.movement.Slippy;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Slippy: the block friction your own ground movement reads. */
@Mixin(LivingEntity.class)
public abstract class SlippyMixin {
    @ModifyExpressionValue(method = "travelInAir", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
    private float prism$slippy(float friction) {
        if ((Object) this != Minecraft.getInstance().player) return friction;
        Slippy s = Prism.modules().get(Slippy.class);
        return s != null && s.isEnabled() ? s.apply(friction) : friction;
    }
}
