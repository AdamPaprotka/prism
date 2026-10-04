package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.render.ESP;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void prism$tickStart(CallbackInfo ci) {
        Prism.onTickStart();
    }

    /** Timer Balance: every client tick is checked against the Grim balance before it runs. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;tick()V"))
    private void prism$timerTick(Minecraft self, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var timer = Prism.modules() == null ? null : Prism.modules().get(dev.prismglass.module.world.Timer.class);
        if (timer == null || timer.beforeTick()) original.call(self);
    }

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void prism$outline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ESP esp = Prism.modules().get(ESP.class);
        if (esp != null && esp.isEnabled() && esp.glows(entity)) cir.setReturnValue(true);
    }

    /** Grim/NCP-safe reach & hitboxes: re-pick the crosshair target against the server-accepted box. */
    @Inject(method = "pick", at = @At("TAIL"))
    private void prism$crosshair(float tickDelta, CallbackInfo ci) {
        dev.prismglass.util.HitboxUtil.extendCrosshair(tickDelta);
    }
}
