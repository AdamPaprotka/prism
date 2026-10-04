package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.prismglass.Prism;
import dev.prismglass.manager.MovementHooks;
import dev.prismglass.module.combat.Hitboxes;
import dev.prismglass.module.movement.Velocity;
import dev.prismglass.module.render.ESP;
import dev.prismglass.module.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    private boolean prism$isSelf() {
        return (Object) this == Minecraft.getInstance().player;
    }

    @ModifyReturnValue(method = "getPickRadius", at = @At("RETURN"))
    private float prism$hitboxes(float original) {
        Hitboxes hb = Prism.modules().get(Hitboxes.class);
        return hb != null && hb.isEnabled() ? original + hb.expand((Entity) (Object) this) : original;
    }

    @WrapOperation(method = "moveRelative", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getYRot()F"))
    private float prism$moveFixYaw(Entity self, Operation<Float> original) {
        float yaw = original.call(self);
        return prism$isSelf() ? MovementHooks.velocityYaw(yaw) : yaw;
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void prism$noEntityPush(Entity entity, CallbackInfo ci) {
        if (!prism$isSelf()) return;
        Velocity v = Prism.modules().get(Velocity.class);
        if (v != null && v.isEnabled() && v.noEntityPush()) ci.cancel();
    }

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void prism$freecamLook(double dx, double dy, CallbackInfo ci) {
        if (!prism$isSelf()) return;
        Freecam fc = Prism.modules().get(Freecam.class);
        if (fc != null && fc.isEnabled()) {
            fc.rotate(dx, dy);
            ci.cancel();
        }
    }

    @ModifyReturnValue(method = "getTeamColor", at = @At("RETURN"))
    private int prism$glowColor(int original) {
        ESP esp = Prism.modules().get(ESP.class);
        if (esp != null && esp.isEnabled()) return esp.glowColor((Entity) (Object) this, original);
        return original;
    }
}
