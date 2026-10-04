package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.module.movement.NoSlow;
import dev.prismglass.module.movement.Velocity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void prism$postTick(CallbackInfo ci) {
        Prism.modules().onPostTick();
    }

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void prism$preMovement(CallbackInfo ci) {
        dev.prismglass.module.movement.Step step = Prism.modules().get(dev.prismglass.module.movement.Step.class);
        if (step != null && step.isEnabled()) step.beforeSend();
        Prism.rotations().preSend();
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void prism$postMovement(CallbackInfo ci) {
        Prism.rotations().postSend();
    }

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 prism$move(Vec3 movement, MoverType type) {
        if (type != MoverType.SELF) return movement;
        MoveEvent event = new MoveEvent(movement);
        Prism.modules().onMove(event);
        return event.toVec();
    }

    @WrapOperation(method = "modifyInput", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z", ordinal = 0))
    private boolean prism$noSlow(LocalPlayer self, Operation<Boolean> original) {
        NoSlow noSlow = Prism.modules().get(NoSlow.class);
        if (noSlow != null && noSlow.isEnabled() && noSlow.items()) return false;
        return original.call(self);
    }

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void prism$noBlockPush(double x, double z, CallbackInfo ci) {
        Velocity v = Prism.modules().get(Velocity.class);
        if (v != null && v.isEnabled() && v.noBlockPush()) ci.cancel();
    }
}
