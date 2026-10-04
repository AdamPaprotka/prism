package dev.prismglass.mixin;

import dev.prismglass.streamproof.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public abstract class GlDeviceMixin {
    /** The frame is recorded: show Prism's layer in the capture-excluded overlay window, then swap. */
    @Inject(method = "presentFrame", at = @At("HEAD"))
    private void prism$present(CallbackInfo ci) {
        Overlay.present();
    }
}
