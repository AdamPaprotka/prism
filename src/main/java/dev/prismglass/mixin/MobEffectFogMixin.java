package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.prismglass.module.player.PotionSpoof;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** PotionSpoof: the Blindness and Darkness fogs don't apply while hidden. */
@Mixin(MobEffectFogEnvironment.class)
public abstract class MobEffectFogMixin {
    @Shadow public abstract net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> getMobEffect();

    @ModifyReturnValue(method = "isApplicable", at = @At("RETURN"))
    private boolean prism$hideFog(boolean original, FogType fogType, Entity entity) {
        return original && !PotionSpoof.hides(getMobEffect());
    }
}
