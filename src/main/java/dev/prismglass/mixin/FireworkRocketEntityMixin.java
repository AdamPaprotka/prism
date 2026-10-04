package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.prismglass.Prism;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A rocket boosts its glider along their look: ours along the silent rotation (what the server simulates). */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 prism$boostLook(LivingEntity glider, Operation<Vec3> original) {
        return Prism.rotations() != null && Prism.rotations().silentLookFor(glider) ? Prism.rotations().silentLook() : original.call(glider);
    }
}
