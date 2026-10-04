package dev.prismglass.mixin;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import dev.prismglass.streamproof.Overlay;
import java.util.Optional;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * StreamProof needs the overlay window on top of Minecraft, which exclusive fullscreen doesn't allow. While it's on,
 * "fullscreen" is a borderless window covering the monitor (looks the same, same resolution).
 *
 * <p>Only a HEAD inject (no @Redirect): some MixinExtras builds shipped with Fabric Loader crash on redirects here.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow @Final private long handle;
    @Shadow @Final private ScreenManager screenManager;
    @Shadow private Optional<VideoMode> preferredFullscreenVideoMode;
    @Shadow private boolean fullscreen;
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private boolean isResized;

    @Shadow private static int allowedWindowMinSize(int size) { throw new AssertionError(); }

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void prism$setMode(CallbackInfo ci) {
        boolean borderlessNow = Overlay.borderless;
        if (fullscreen && Overlay.wantsBorderless()) {
            Monitor monitor = screenManager.findBestMonitor((Window) (Object) this);
            if (monitor == null) return; // vanilla handles "no monitor"
            VideoMode mode = monitor.getPreferredVidMode(preferredFullscreenVideoMode);
            // a maximized window stays clamped to the work area (gaps top/bottom), so un-maximize first;
            // the restore updates x/y/width/height through the move/size callbacks before they're saved below
            if (!borderlessNow && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
                Overlay.remaximize = true;
                GLFW.glfwRestoreWindow(handle);
            }
            if (!borderlessNow && GLFW.glfwGetWindowMonitor(handle) == 0L) {
                windowedX = x;
                windowedY = y;
                windowedWidth = allowedWindowMinSize(width);
                windowedHeight = allowedWindowMinSize(height);
            }
            // locals: the restore/style changes fire Minecraft's move/size callbacks, which overwrite x/y/width/height
            int bx = monitor.getX(), by = monitor.getY();
            int bw = allowedWindowMinSize(mode.getWidth());
            // one pixel taller than the monitor (the extra row is off-screen): an undecorated GL window that exactly
            // covers the monitor gets silently promoted to exclusive fullscreen by NVIDIA drivers, hiding the overlay
            int bh = allowedWindowMinSize(mode.getHeight()) + (Boolean.getBoolean("prismglass.exactfs") ? 0 : 1);
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(handle, 0L, bx, by, bw, bh, GLFW.GLFW_DONT_CARE);
            x = bx;
            y = by;
            width = bw;
            height = bh;
            isResized = true;
            Overlay.borderlessRect = new int[]{bx, by, bw, bh};
            Overlay.borderless = true;
            ci.cancel();
            return;
        }
        if (!borderlessNow) return; // plain vanilla
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        Overlay.borderless = false;
        if (!fullscreen) return; // vanilla restores the saved windowed size/position
        // borderless -> exclusive fullscreen: vanilla would think we were windowed and overwrite the saved size
        Monitor monitor = screenManager.findBestMonitor((Window) (Object) this);
        if (monitor == null) return;
        VideoMode mode = monitor.getPreferredVidMode(preferredFullscreenVideoMode);
        x = 0;
        y = 0;
        width = allowedWindowMinSize(mode.getWidth());
        height = allowedWindowMinSize(mode.getHeight());
        isResized = true;
        GLFW.glfwSetWindowMonitor(handle, monitor.getMonitor(), x, y, width, height, mode.getRefreshRate());
        ci.cancel();
    }

    /** Back to windowed after borderless: maximize again if it was maximized before. */
    @Inject(method = "setMode", at = @At("TAIL"))
    private void prism$remaximize(CallbackInfo ci) {
        if (!fullscreen && Overlay.remaximize) {
            Overlay.remaximize = false;
            GLFW.glfwMaximizeWindow(handle);
        }
    }
}
