package dev.prismglass.mixin;

import dev.prismglass.streamproof.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 (renderpearl): the GL backend swaps in GlSurface.present() (SDL_GL_SwapWindow), what GlDevice.presentFrame()
 * (glfwSwapBuffers) used to be. GL backend only; with the Vulkan backend this hook never fires.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlSurface")
public abstract class GlDeviceMixin {
    /** The frame is recorded: show Prism's layer in the capture-excluded overlay window, then swap. */
    @Inject(method = "present", at = @At("HEAD"))
    private void prism$present(CallbackInfo ci) {
        Overlay.present();
    }
}
