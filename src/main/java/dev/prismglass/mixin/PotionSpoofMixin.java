package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.prismglass.module.player.PotionSpoof;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PotionSpoof: the blend factor is what the screen effects read (Nausea warp in GameRenderer and Gui, the Darkness
 * lightmap pulse). Gameplay reads hasEffect, which stays true.
 */
@Mixin(LivingEntity.class)
public abstract class PotionSpoofMixin {
    @ModifyReturnValue(method = "getEffectBlendFactor", at = @At("RETURN"))
    private float prism$hideEffect(float original, Holder<MobEffect> effect, float partialTicks) {
        if (original == 0 || (Object) this != Minecraft.getInstance().player) return original;
        return PotionSpoof.hides(effect) ? 0 : original;
    }
}
