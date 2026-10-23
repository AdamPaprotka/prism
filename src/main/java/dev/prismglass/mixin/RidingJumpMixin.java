package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.prismglass.Prism;
import dev.prismglass.module.movement.EntityControl;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** EntityControl MaxJump: any horse jump charge reads full. */
@Mixin(LocalPlayer.class)
public abstract class RidingJumpMixin {
    @ModifyReturnValue(method = "getJumpRidingScale", at = @At("RETURN"))
    private float prism$maxJump(float scale) {
        EntityControl ec = Prism.modules().get(EntityControl.class);
        return ec != null && ec.isEnabled() && ec.maxJump.get() && scale > 0 ? 1f : scale;
    }
}
