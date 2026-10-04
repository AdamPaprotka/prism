package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.client.StreamProof;
import dev.prismglass.streamproof.Overlay;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Dev only (-Pfstest): F11 with StreamProof from a normal and from a maximized window; logs geometry, no captures. */
public final class FullscreenTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int t;

    private FullscreenTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String what) {
        long h = mc.getWindow().handle();
        int[] x = new int[1], y = new int[1], w = new int[1], hh = new int[1], fw = new int[1], fh = new int[1];
        GLFW.glfwGetWindowPos(h, x, y);
        GLFW.glfwGetWindowSize(h, w, hh);
        GLFW.glfwGetFramebufferSize(h, fw, fh);
        Prism.LOG.info("[fstest] {}: fullscreen={} pos={},{} size={}x{} fb={}x{} decorated={} maximized={} target={}x{} {}", what,
            mc.getWindow().isFullscreen(), x[0], y[0], w[0], hh[0], fw[0], fh[0], GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_DECORATED),
            GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_MAXIMIZED), mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, Overlay.debugWin32(h));
    }

    private static void tick() {
        if (++t == 60) Prism.modules().get(StreamProof.class).setEnabled(true);
        if (t == 80) log("windowed");
        if (t == 90) mc.getWindow().toggleFullScreen();
        if (t == 130) log("F11 from normal");
        if (t == 140) mc.getWindow().toggleFullScreen();
        if (t == 180) { log("back"); GLFW.glfwMaximizeWindow(mc.getWindow().handle()); }
        if (t == 220) log("maximized");
        if (t == 230) mc.getWindow().toggleFullScreen();
        if (t == 270) log("F11 from maximized");
        if (t == 280) mc.getWindow().toggleFullScreen();
        if (t == 320) { log("back from maximized"); Runtime.getRuntime().halt(0); }
    }
}
