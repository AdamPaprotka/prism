package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.prismglass.Prism;
import dev.prismglass.module.render.CameraTweaks;
import dev.prismglass.module.render.CustomFov;
import dev.prismglass.module.render.Freecam;
import dev.prismglass.module.render.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    /**
     * Freecam's position and rotation go in before the culling frustum is built from them (right before
     * getViewRotationMatrix), so chunks and entities are culled for where the freecam looks, not the player.
     */
    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getViewRotationMatrix(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;"))
    private void prism$freecamBeforeCulling(DeltaTracker deltaTracker, CallbackInfo ci) {
        prism$applyFreecam(deltaTracker);
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void prism$freecam(DeltaTracker deltaTracker, CallbackInfo ci) {
        prism$applyFreecam(deltaTracker);
    }

    private void prism$applyFreecam(DeltaTracker deltaTracker) {
        Freecam fc = Prism.modules().get(Freecam.class);
        if (fc != null && fc.isEnabled()) {
            Camera self = (Camera) (Object) this;
            var pos = fc.position(deltaTracker.getGameTimeDeltaPartialTick(true));
            self.setPosition(pos.x, pos.y, pos.z);
            self.setRotation(fc.yaw(), fc.pitch());
        }
    }

    /** CameraTweaks freelook: the camera follows the freelook angles instead of the player's head. */
    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float prism$freelookYaw(Entity entity, float partialTicks, Operation<Float> original) {
        CameraTweaks ct = Prism.modules().get(CameraTweaks.class);
        return ct != null && ct.freelooking() && entity == Minecraft.getInstance().player ? ct.lookYaw() : original.call(entity, partialTicks);
    }

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float prism$freelookPitch(Entity entity, float partialTicks, Operation<Float> original) {
        CameraTweaks ct = Prism.modules().get(CameraTweaks.class);
        return ct != null && ct.freelooking() && entity == Minecraft.getInstance().player ? ct.lookPitch() : original.call(entity, partialTicks);
    }

    /** CameraTweaks stretch: the world projection is built for a narrower screen than the window. */
    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setupPerspective(FFFFF)V"), index = 3)
    private float prism$stretchWidth(float width) {
        CameraTweaks ct = Prism.modules().get(CameraTweaks.class);
        return ct != null ? ct.projectionWidth(width, Minecraft.getInstance().getWindow().getHeight()) : width;
    }

    @ModifyArg(method = "createProjectionMatrixForCulling", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFFZ)Lorg/joml/Matrix4f;"), index = 1)
    private float prism$stretchCulling(float aspect) {
        CameraTweaks ct = Prism.modules().get(CameraTweaks.class);
        return ct != null ? ct.cullAspect(aspect) : aspect;
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
