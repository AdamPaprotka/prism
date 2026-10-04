package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.prismglass.Prism;
import dev.prismglass.manager.MovementHooks;
import dev.prismglass.module.movement.AntiLevitation;
import dev.prismglass.module.movement.Step;
import dev.prismglass.module.render.Fullbright;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    private boolean prism$isSelf() {
        return (Object) this == Minecraft.getInstance().player;
    }

    @Inject(method = "hasEffect", at = @At("HEAD"), cancellable = true)
    private void prism$effects(Holder<MobEffect> effect, CallbackInfoReturnable<Boolean> cir) {
        if (!prism$isSelf()) return;
        if (effect == MobEffects.NIGHT_VISION) {
            Fullbright fb = Prism.modules().get(Fullbright.class);
            if (fb != null && fb.isEnabled()) cir.setReturnValue(true);
        } else if (effect == MobEffects.LEVITATION) {
            AntiLevitation al = Prism.modules().get(AntiLevitation.class);
            if (al != null && al.isEnabled()) cir.setReturnValue(false);
        }
    }

    @ModifyReturnValue(method = "maxUpStep", at = @At("RETURN"))
    private float prism$step(float original) {
        if (!prism$isSelf()) return original;
        Step step = Prism.modules().get(Step.class);
        return step != null && step.isEnabled() ? step.height(original) : original;
    }

    /** Sprint-jump boost direction must follow the server yaw too (MoveFix). */
    @WrapOperation(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
    private float prism$jumpYaw(LivingEntity self, Operation<Float> original) {
        float yaw = original.call(self);
        return prism$isSelf() ? MovementHooks.velocityYaw(yaw) : yaw;
    }

    /** Elytra physics with the silent rotation (see RotationManager#silentLookFor). */
    @WrapOperation(method = "updateFallFlyingMovement", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
    private net.minecraft.world.phys.Vec3 prism$elytraLook(LivingEntity self, Operation<net.minecraft.world.phys.Vec3> original) {
        return Prism.rotations() != null && Prism.rotations().silentLookFor(self) ? Prism.rotations().silentLook() : original.call(self);
    }

    @WrapOperation(method = "updateFallFlyingMovement", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;getXRot()F"))
    private float prism$elytraPitch(LivingEntity self, Operation<Float> original) {
        return Prism.rotations() != null && Prism.rotations().silentLookFor(self) ? Prism.rotations().getMovePitch() : original.call(self);
    }
}
