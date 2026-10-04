package dev.prismglass.streamproof;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import dev.prismglass.Prism;
import dev.prismglass.module.client.StreamProof;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.*;

import static org.lwjgl.glfw.GLFW.*;

/**
 * StreamProof: Prism's visuals (HUD, ClickGUI, ESP/Xray boxes...) are drawn into their own render target and shown
 * in a second, transparent, click-through, always-on-top window that sits exactly over Minecraft and is excluded from
 * screen capture (SetWindowDisplayAffinity WDA_EXCLUDEFROMCAPTURE). OBS (game/window/display capture), Discord and
 * screenshots only ever see the vanilla frame; you see both.
 *
 * <p>The overlay is an owned window of Minecraft's (always directly above it, minimized with it, not "always on top"
 * over other apps) and follows Minecraft's move/resize callbacks, so it stays aligned even mid-drag.
 *
 * <p>The overlay window shares the GL context's objects with Minecraft, so presenting is: fence, switch context, draw
 * the shared texture full screen, swap, switch back. Exclusive fullscreen would cover the overlay, so fullscreen is
 * turned into borderless fullscreen while this is on (see WindowMixin).
 */
public final class Overlay {
    private static final Minecraft mc = Minecraft.getInstance();

    /** Render3D's output: the overlay target while StreamProof runs, otherwise (null) the main target. */
    public static final OutputTarget OUTPUT = new OutputTarget("prism_streamproof", () -> active() ? Overlay.target : null);

    private static TextureTarget target;
    private static long window;
    private static GLCapabilities caps;
    private static int program, vao, sampler;
    private static boolean failed, shown, lastWantBorderless;
    private static int lastX = Integer.MIN_VALUE, lastY, lastW, lastH;
    private static org.lwjgl.glfw.GLFWWindowPosCallback posHook;
    private static org.lwjgl.glfw.GLFWWindowSizeCallback sizeHook;
    private static org.lwjgl.glfw.GLFWWindowPosCallbackI prevPos;
    private static org.lwjgl.glfw.GLFWWindowSizeCallbackI prevSize;

    /** Set while fullscreen is really a borderless window (WindowMixin). */
    public static boolean borderless;
    /** The window was maximized before borderless fullscreen; maximize it again when leaving. */
    public static boolean remaximize;
    /** Where borderless fullscreen must be; Windows can apply a pending un-maximize after we've placed it. */
    public static int[] borderlessRect;
    private static int enforceTries;

    // dev test switches
    public static boolean debugForceVisible, debugIncludeInCapture;
    public static volatile String debugReadbackFile;

    private Overlay() {}

    private static StreamProof module() {
        return Prism.modules() == null ? null : Prism.modules().get(StreamProof.class);
    }

    private static boolean wanted() {
        StreamProof sp = module();
        return sp != null && sp.isEnabled() && !failed;
    }

    /** ClickGUI: swap the OS cursor (visible to capture) for one drawn in the hidden layer. */
    public static boolean wantsSoftCursor() {
        StreamProof sp = module();
        return active() && sp.hideCursor.get();
    }

    public static boolean wantsBorderless() {
        StreamProof sp = module();
        return wanted() && sp.borderless.get();
    }

    /** True when Prism visuals must go to the overlay this frame. */
    public static boolean active() { return window != 0 && target != null && wanted(); }

    // ---- per frame ---------------------------------------------------------------------------------------

    /** Start of Prism's GUI content (HUD, ClickGUI): everything from here on goes to the hidden layer. */
    public static void markGui() {
        if (active()) ((PrismGuiState) mc.gameRenderer.getGameRenderState().guiRenderState).prism$markOverlay();
    }

    /** Render3D.begin: give the overlay the world's depth so non-xray boxes are still hidden behind walls. */
    public static void beginWorld() {
        if (!active()) return;
        RenderTarget main = mc.getMainRenderTarget();
        // size changes are applied at the end of the frame (present); for one frame after a resize, skip occlusion
        if (target.width == main.width && target.height == main.height) target.copyDepthFrom(main);
    }

    /** GlDevice.presentFrame HEAD: main context current, frame fully recorded. */
    public static void present() {
        RenderSystem.assertOnRenderThread();
        enforceBorderless();
        boolean want = wanted();
        boolean wantBorderless = wantsBorderless();
        if (wantBorderless != lastWantBorderless) {
            lastWantBorderless = wantBorderless;
            if (mc.getWindow().isFullscreen()) ((dev.prismglass.mixin.WindowAccessor) (Object) mc.getWindow()).prism$setMode();
        }
        if (want && window == 0) create();
        if (!want && window != 0) destroy();
        if (window == 0) return;
        try {
            syncWindow();
            if (shown) blit();
            clearForNextFrame();
        } catch (Throwable t) {
            fail("overlay error", t);
        }
    }

    private static void enforceBorderless() {
        if (!borderless || borderlessRect == null || !mc.getWindow().isFullscreen()) { enforceTries = 0; return; }
        long h = mc.getWindow().handle();
        int[] x = new int[1], y = new int[1], w = new int[1], hh = new int[1];
        glfwGetWindowPos(h, x, y);
        glfwGetWindowSize(h, w, hh);
        int[] r = borderlessRect;
        if (x[0] == r[0] && y[0] == r[1] && w[0] == r[2] && hh[0] == r[3]) { enforceTries = 0; return; }
        if (enforceTries++ > 20) return; // something else insists; don't fight it forever
        if (Boolean.getBoolean("prismglass.fstest")) Prism.LOG.info("[fstest] enforce try {}: at {},{} {}x{} want {}", enforceTries, x[0], y[0], w[0], hh[0], java.util.Arrays.toString(r));
        if (glfwGetWindowAttrib(h, GLFW_MAXIMIZED) == GLFW_TRUE) glfwRestoreWindow(h);
        glfwSetWindowMonitor(h, 0L, r[0], r[1], r[2], r[3], GLFW_DONT_CARE);
    }

    private static void clearForNextFrame() {
        RenderTarget main = mc.getMainRenderTarget();
        if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height);
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
    }

    private static void syncWindow() {
        long main = mc.getWindow().handle();
        // owned by Minecraft's window (other apps cover it like the game), and also hidden whenever the game
        // isn't the focused window, so it can never show over anything else
        boolean visible = debugForceVisible
            || (glfwGetWindowAttrib(main, GLFW_FOCUSED) == GLFW_TRUE && glfwGetWindowAttrib(main, GLFW_ICONIFIED) == GLFW_FALSE);
        int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
        glfwGetWindowPos(main, x, y);
        glfwGetWindowSize(main, w, h);
        if (x[0] != lastX || y[0] != lastY || w[0] != lastW || h[0] != lastH) {
            lastX = x[0]; lastY = y[0]; lastW = w[0]; lastH = h[0];
            glfwSetWindowPos(window, x[0], y[0]);
            glfwSetWindowSize(window, Math.max(1, w[0]), Math.max(1, h[0]));
        }
        if (visible != shown) {
            if (visible) glfwShowWindow(window); else glfwHideWindow(window);
            shown = visible;
        }
    }

    private static void blit() {
        long main = glfwGetCurrentContext();
        GLCapabilities mainCaps = GL.getCapabilities();
        int tex = ((GlTexture) target.getColorTexture()).glId();
        long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
        glfwMakeContextCurrent(window);
        GL.setCapabilities(caps);
        try {
            GL32.glWaitSync(fence, 0, GL32.GL_TIMEOUT_IGNORED);
            int[] fw = new int[1], fh = new int[1];
            glfwGetFramebufferSize(window, fw, fh);
            GL11.glViewport(0, 0, fw[0], fh[0]);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL33.glBindSampler(0, sampler);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            if (debugReadbackFile != null) readback(fw[0], fh[0]);
            glfwSwapBuffers(window);
        } finally {
            glfwMakeContextCurrent(main);
            GL.setCapabilities(mainCaps);
            GL32.glDeleteSync(fence);
        }
    }

    // ---- window lifecycle -----------------------------------------------------------------------------------

    private static void create() {
        long main = mc.getWindow().handle();
        GLCapabilities mainCaps = GL.getCapabilities();
        int[] w = new int[1], h = new int[1];
        glfwGetWindowSize(main, w, h);

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, glfwGetWindowAttrib(main, GLFW_CONTEXT_VERSION_MAJOR));
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, glfwGetWindowAttrib(main, GLFW_CONTEXT_VERSION_MINOR));
        glfwWindowHint(GLFW_OPENGL_PROFILE, glfwGetWindowAttrib(main, GLFW_OPENGL_PROFILE));
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, glfwGetWindowAttrib(main, GLFW_OPENGL_FORWARD_COMPAT));
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE);
        glfwWindowHint(GLFW_MOUSE_PASSTHROUGH, GLFW_TRUE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        window = glfwCreateWindow(Math.max(1, w[0]), Math.max(1, h[0]), "Prism", 0L, main);
        glfwDefaultWindowHints();
        if (window == 0) { fail("couldn't create the overlay window", null); return; }
        if (glfwGetWindowAttrib(window, GLFW_TRANSPARENT_FRAMEBUFFER) != GLFW_TRUE) {
            fail("your GPU driver can't make transparent windows", null);
            return;
        }

        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        Pointer p = new Pointer(hwnd);
        // tool window: no taskbar button, not in alt-tab; never takes focus
        long ex = User32.INSTANCE.GetWindowLongPtrW(p, GWL_EXSTYLE);
        User32.INSTANCE.SetWindowLongPtrW(p, GWL_EXSTYLE, (ex | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE) & ~WS_EX_APPWINDOW);
        // owned by the game window: always right above it in z-order, hidden when it's minimized
        User32.INSTANCE.SetWindowLongPtrW(p, GWLP_HWNDPARENT, GLFWNativeWin32.glfwGetWin32Window(main));
        if (!setExcluded(true)) {
            fail("capture exclusion needs Windows 10 (2004) or newer", null);
            return;
        }

        glfwMakeContextCurrent(window);
        try {
            caps = GL.createCapabilities();
            glfwSwapInterval(0); // never wait on the overlay's own vsync
            program = buildProgram();
            vao = GL30.glGenVertexArrays();
            sampler = GL33.glGenSamplers();
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } finally {
            glfwMakeContextCurrent(main);
            GL.setCapabilities(mainCaps);
        }
        RenderTarget m = mc.getMainRenderTarget();
        target = new TextureTarget("prism streamproof", m.width, m.height, true);
        clearForNextFrame();
        lastX = Integer.MIN_VALUE;
        shown = false;
        hookMoves(main);
        Prism.LOG.info("[streamproof] overlay window ready ({}x{})", w[0], h[0]);
    }

    /**
     * Windows runs a modal loop while a window is dragged or resized, so no frames (and no syncWindow) happen until
     * you let go; Minecraft's GLFW move/size callbacks still fire, so the overlay follows from there.
     */
    private static void hookMoves(long main) {
        posHook = org.lwjgl.glfw.GLFWWindowPosCallback.create((w, x, y) -> {
            if (prevPos != null) prevPos.invoke(w, x, y);
            if (window != 0) { glfwSetWindowPos(window, x, y); lastX = x; lastY = y; }
        });
        sizeHook = org.lwjgl.glfw.GLFWWindowSizeCallback.create((w, width, height) -> {
            if (prevSize != null) prevSize.invoke(w, width, height);
            if (window != 0 && width > 0 && height > 0) { glfwSetWindowSize(window, width, height); lastW = width; lastH = height; }
        });
        prevPos = glfwSetWindowPosCallback(main, posHook);
        prevSize = glfwSetWindowSizeCallback(main, sizeHook);
    }

    private static void unhookMoves() {
        if (posHook == null) return;
        long main = mc.getWindow().handle();
        glfwSetWindowPosCallback(main, prevPos);
        glfwSetWindowSizeCallback(main, prevSize);
        posHook.free();
        sizeHook.free();
        posHook = null;
        sizeHook = null;
        prevPos = null;
        prevSize = null;
    }

    private static void destroy() {
        unhookMoves();
        if (window != 0) {
            long main = glfwGetCurrentContext();
            GLCapabilities mainCaps = GL.getCapabilities();
            glfwMakeContextCurrent(window);
            GL.setCapabilities(caps);
            GL20.glDeleteProgram(program);
            GL30.glDeleteVertexArrays(vao);
            GL33.glDeleteSamplers(sampler);
            glfwMakeContextCurrent(main);
            GL.setCapabilities(mainCaps);
            glfwDestroyWindow(window);
        }
        window = 0;
        caps = null;
        shown = false;
        if (target != null) { target.destroyBuffers(); target = null; }
    }

    private static void fail(String why, Throwable t) {
        failed = true;
        Prism.LOG.error("[streamproof] disabled: {}", why, t);
        destroy();
        StreamProof sp = module();
        if (sp != null) {
            sp.setEnabled(false);
            dev.prismglass.util.ChatUtil.error("StreamProof off: " + why);
        }
        failed = false; // allow another try when re-enabled
    }

    /** WDA_EXCLUDEFROMCAPTURE on/off; the dev test turns it off once to prove the overlay itself composites. */
    public static boolean setExcluded(boolean excluded) {
        if (window == 0) return false;
        Pointer p = new Pointer(GLFWNativeWin32.glfwGetWin32Window(window));
        return User32.INSTANCE.SetWindowDisplayAffinity(p, excluded ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE);
    }

    public static int affinity() {
        if (window == 0) return -1;
        IntByReference out = new IntByReference();
        User32.INSTANCE.GetWindowDisplayAffinity(new Pointer(GLFWNativeWin32.glfwGetWin32Window(window)), out);
        return out.getValue();
    }

    public static boolean isShown() { return shown; }

    /** Dev test: Win32's view of a GLFW window (outer rect, maximized state). */
    public static String debugWin32(long glfwWindow) {
        Pointer p = new Pointer(GLFWNativeWin32.glfwGetWin32Window(glfwWindow));
        int[] r = new int[4];
        User32.INSTANCE.GetWindowRect(p, r);
        return "rect=" + r[0] + "," + r[1] + "-" + r[2] + "," + r[3] + " zoomed=" + User32.INSTANCE.IsZoomed(p);
    }

    /** Dev test: put the overlay back on top of the (test-floated) game window. */
    public static void debugRaise() {
        if (window == 0) return;
        glfwSetWindowAttrib(window, GLFW_FLOATING, GLFW_FALSE);
        glfwSetWindowAttrib(window, GLFW_FLOATING, GLFW_TRUE);
    }

    // ---- GL bits ---------------------------------------------------------------------------------------------

    private static int buildProgram() {
        String vs = """
            #version 150
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
        // the target holds premultiplied colour + coverage alpha, which is what DWM composites
        String fs = """
            #version 150
            uniform sampler2D Overlay;
            in vec2 uv;
            out vec4 color;
            void main() { color = texture(Overlay, uv); }
            """;
        int v = shader(GL20.GL_VERTEX_SHADER, vs), f = shader(GL20.GL_FRAGMENT_SHADER, fs);
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, v);
        GL20.glAttachShader(prog, f);
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) throw new IllegalStateException(GL20.glGetProgramInfoLog(prog));
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        GL20.glUseProgram(prog);
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, "Overlay"), 0);
        return prog;
    }

    private static int shader(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
        return s;
    }

    private static void readback(int w, int h) {
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
        try {
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = (x + y * w) * 4; // GL rows are bottom-up
                int r = buf.get(i) & 0xFF, g = buf.get(i + 1) & 0xFF, b = buf.get(i + 2) & 0xFF, a = buf.get(i + 3) & 0xFF;
                // un-premultiply for a normal PNG
                if (a > 0 && a < 255) { r = Math.min(255, r * 255 / a); g = Math.min(255, g * 255 / a); b = Math.min(255, b * 255 / a); }
                img.setRGB(x, h - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
            }
            javax.imageio.ImageIO.write(img, "png", new java.io.File(debugReadbackFile));
            Prism.LOG.info("[streamproof] overlay readback saved to {}", debugReadbackFile);
        } catch (Exception e) {
            Prism.LOG.error("[streamproof] readback failed", e);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
            debugReadbackFile = null;
        }
    }

    // ---- Win32 -------------------------------------------------------------------------------------------------

    private static final int GWL_EXSTYLE = -20, GWLP_HWNDPARENT = -8;
    private static final long WS_EX_TOOLWINDOW = 0x00000080L, WS_EX_NOACTIVATE = 0x08000000L, WS_EX_APPWINDOW = 0x00040000L;
    private static final int WDA_NONE = 0x0, WDA_EXCLUDEFROMCAPTURE = 0x11;

    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);

        boolean SetWindowDisplayAffinity(Pointer hwnd, int affinity);

        boolean GetWindowDisplayAffinity(Pointer hwnd, IntByReference affinity);

        long GetWindowLongPtrW(Pointer hwnd, int index);

        boolean GetWindowRect(Pointer hwnd, int[] rect);

        boolean IsZoomed(Pointer hwnd);

        long SetWindowLongPtrW(Pointer hwnd, int index, long value);
    }
}
