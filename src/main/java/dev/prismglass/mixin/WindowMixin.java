package dev.prismglass.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.prismglass.streamproof.Overlay;
import dev.prismglass.streamproof.PrismWindow;
import java.nio.IntBuffer;
import net.minecraft.util.Util;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * StreamProof needs the overlay window on top of Minecraft, which exclusive fullscreen doesn't allow. While it's on,
 * "fullscreen" is always the borderless window covering the monitor (looks the same, same resolution).
 *
 * <p>26.3 (SDL3): vanilla already does borderless fullscreen on Windows unless "Exclusive Fullscreen" is on, as a
 * plain window one pixel wider than the monitor (the extra column is off-screen; an undecorated GL window that exactly
 * covers the monitor gets silently promoted to exclusive fullscreen by NVIDIA drivers, hiding the overlay). So this
 * only forces that path, fixes F11 from a maximized window and keeps the overlay's own window events away from
 * Minecraft's Window.
 */
@Mixin(Window.class)
public abstract class WindowMixin implements PrismWindow {
    @Shadow @Final private long handle;
    @Shadow private boolean fullscreenRequested;
    @Shadow private boolean fullscreen;
    @Shadow private boolean borderlessFullscreen;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Override
    public boolean prism$fullscreen() { return fullscreen; }

    @Override
    public boolean prism$borderless() { return borderlessFullscreen; }

    /** StreamProof: never SDL exclusive fullscreen, even with vanilla's Exclusive Fullscreen option on. */
    @Inject(method = "useBorderlessFullscreenWindow", at = @At("HEAD"), cancellable = true)
    private void prism$forceBorderless(CallbackInfoReturnable<Boolean> cir) {
        if (Util.getPlatform() == Util.OS.WINDOWS && Overlay.wantsBorderless()) cir.setReturnValue(true);
    }

    /**
     * F11 from a maximized window: vanilla saves the maximized rect as the windowed size and only un-maximizes inside
     * applyBorderlessFullscreenWindow, so leaving fullscreen gives a work-area-sized window that isn't maximized.
     * Un-maximize first and save the real windowed rect; maximize again when leaving (TAIL).
     */
    @Inject(method = "setMode", at = @At("HEAD"))
    private void prism$unmaximize(CallbackInfo ci) {
        if (!fullscreenRequested || fullscreen || !Overlay.wantsBorderless()) return;
        if ((SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_MAXIMIZED) == 0) return;
        Overlay.remaximize = true;
        SDLVideo.SDL_RestoreWindow(handle);
        SDLVideo.SDL_SyncWindow(handle);
        // the restore's move/resize events are only handled with the next event poll, but vanilla saves
        // x/y/width/height as the windowed rect right after this: read the restored rect from SDL now
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer a = stack.mallocInt(1), b = stack.mallocInt(1);
            if (SDLVideo.SDL_GetWindowPosition(handle, a, b)) { x = a.get(0); y = b.get(0); }
            if (SDLVideo.SDL_GetWindowSize(handle, a, b)) { width = a.get(0); height = b.get(0); }
        }
    }

    @Inject(method = "setMode", at = @At("TAIL"))
    private void prism$afterSetMode(CallbackInfo ci) {
        Overlay.borderless = borderlessFullscreen;
        // setWindowSizeAndPosition stored where borderless fullscreen must be; Overlay keeps it there
        Overlay.borderlessRect = borderlessFullscreen ? new int[]{x, y, width, height} : null;
        if (!fullscreenRequested && !fullscreen && Overlay.remaximize) {
            Overlay.remaximize = false;
            SDLVideo.SDL_MaximizeWindow(handle);
        }
    }

    /** Dev (-Pexactfs): borderless window exactly the monitor's size, to check whether the driver promotes it. */
    @ModifyArg(method = "applyBorderlessFullscreenWindow",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setWindowSizeAndPosition(IIII)Z"), index = 2)
    private int prism$exactBorderless(int w) {
        return Boolean.getBoolean("prismglass.exactfs") ? w - 1 : w;
    }

    /** The StreamProof overlay is an SDL window too: its move/resize/show events aren't Minecraft's window's. */
    @Inject(method = "handleEvent", at = @At("HEAD"), cancellable = true)
    private void prism$skipOverlayEvents(SDL_Event event, CallbackInfo ci) {
        if (Overlay.isOverlayEvent(event)) ci.cancel();
    }
}
