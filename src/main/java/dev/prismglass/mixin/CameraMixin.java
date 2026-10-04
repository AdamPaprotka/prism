package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.prismglass.Prism;
import dev.prismglass.module.render.CameraTweaks;
import dev.prismglass.module.render.CustomFov;
import dev.prismglass.module.render.Freecam;
import dev.prismglass.module.render.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void prism$freecam(DeltaTracker deltaTracker, CallbackInfo ci) {
        float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(true);
        Freecam fc = Prism.modules().get(Freecam.class);
        if (fc != null && fc.isEnabled()) {
            Camera self = (Camera) (Object) this;
            var pos = fc.position(tickDelta);
            self.setPosition(pos.x, pos.y, pos.z);
            self.setRotation(fc.yaw(), fc.pitch());
        }
    }

    @ModifyReturnValue(method = "isDetached", at = @At("RETURN"))
    private boolean prism$renderSelf(boolean original) {
        Freecam fc = Prism.modules().get(Freecam.class);
        return original || (fc != null && fc.isEnabled());
    }

    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float prism$fov(float fov) {
        CustomFov custom = Prism.modules().get(CustomFov.class);
        if (custom != null && custom.isEnabled()) fov = custom.apply(fov);
        Zoom zoom = Prism.modules().get(Zoom.class);
        if (zoom != null) fov = zoom.apply(fov);
        return fov;
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void prism$cameraClip(float distance, CallbackInfoReturnable<Float> cir) {
        CameraTweaks ct = Prism.modules().get(CameraTweaks.class);
        if (ct != null && ct.isEnabled()) cir.setReturnValue(ct.distance(distance));
    }
}
