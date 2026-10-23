package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.prismglass.Prism;
import dev.prismglass.module.render.HandView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** HandView SwingSpeed: the local player's swing animation length (attack cooldown is separate and untouched). */
@Mixin(LivingEntity.class)
public abstract class SwingSpeedMixin {
    @ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
    private int prism$swingSpeed(int original) {
        if ((Object) this != Minecraft.getInstance().player) return original;
        HandView hv = Prism.modules().get(HandView.class);
        return hv != null && hv.isEnabled() ? hv.swingDuration(original) : original;
    }
}
