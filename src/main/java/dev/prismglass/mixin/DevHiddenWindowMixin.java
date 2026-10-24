package dev.prismglass.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev tests only (-Dprismglass.hidden=true): the game window is created invisible and never takes focus.
 * 26.3: the window comes from the graphics backend's createWindow(title, w, h, SDL flags); vanilla never shows it
 * again by itself, so a hidden window stays hidden.
 */
@Mixin(Window.class)
public abstract class DevHiddenWindowMixin {
    @ModifyArg(method = "createWindow",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3)
    private long prism$hidden(long flags) {
        if (!Boolean.getBoolean("prismglass.hidden")) return flags;
        return flags | SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_NOT_FOCUSABLE;
    }

    /** Going fullscreen (options.txt fullscreen:true) would show the hidden window: stay windowed and invisible. */
    @Inject(method = "applyFullscreen", at = @At("HEAD"), cancellable = true)
    private void prism$noHiddenFullscreen(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.getBoolean("prismglass.hidden")) cir.setReturnValue(true);
    }
}
