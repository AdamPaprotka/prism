package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.client.StreamProof;
import dev.prismglass.streamproof.Overlay;
import dev.prismglass.streamproof.PrismWindow;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;

/** Dev only (-Pfstest): F11 with StreamProof from a normal and from a maximized window; logs geometry, no captures. */
public final class FullscreenTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int t;

    private FullscreenTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String what) {
        long h = mc.getWindow().handle();
        int[] r = Overlay.rect(h), fb = Overlay.pixelSize(h);
        long flags = SDLVideo.SDL_GetWindowFlags(h);
        Prism.LOG.info("[fstest] {}: fullscreen={} pos={},{} size={}x{} fb={}x{} decorated={} maximized={} target={}x{} {}", what,
            ((PrismWindow) (Object) mc.getWindow()).prism$fullscreen(), r[0], r[1], r[2], r[3], fb[0], fb[1],
            (flags & SDLVideo.SDL_WINDOW_BORDERLESS) == 0, (flags & SDLVideo.SDL_WINDOW_MAXIMIZED) != 0,
            mc.gameRenderer.mainRenderTarget().width, mc.gameRenderer.mainRenderTarget().height, Overlay.debugWin32(h));
    }

    /** F11: 26.3's Window has no toggle; the option applies it on the render thread (updateFullscreenIfChanged). */
    private static void toggle() { mc.options.fullscreen().set(!mc.options.fullscreen().get()); }

    private static void tick() {
        if (++t == 60) Prism.modules().get(StreamProof.class).setEnabled(true);
        if (t == 80) log("windowed");
        if (t == 90) toggle();
        if (t == 130) log("F11 from normal");
        if (t == 140) toggle();
        if (t == 180) { log("back"); SDLVideo.SDL_MaximizeWindow(mc.getWindow().handle()); }
        if (t == 220) log("maximized");
        if (t == 230) toggle();
        if (t == 270) log("F11 from maximized");
        if (t == 280) toggle();
        if (t == 320) { log("back from maximized"); Runtime.getRuntime().halt(0); }
    }
}
