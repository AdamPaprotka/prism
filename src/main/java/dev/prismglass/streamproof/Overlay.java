package dev.prismglass.streamproof;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import dev.prismglass.Prism;
import dev.prismglass.module.client.StreamProof;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.util.Util;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;
import org.lwjgl.sdl.SDL_WindowEvent;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.sdl.SDLVideo.*;

/**
 * StreamProof: Prism's visuals (HUD, ClickGUI, ESP/Xray boxes...) are drawn into their own render target and shown
 * in a second, transparent, click-through window that sits exactly over Minecraft and is excluded from screen capture
 * (SetWindowDisplayAffinity WDA_EXCLUDEFROMCAPTURE). OBS (game/window/display capture), Discord and screenshots only
 * ever see the vanilla frame; you see both.
 *
 * <p>The overlay is an owned window of Minecraft's (always directly above it, minimized with it, not "always on top"
 * over other apps) and follows Minecraft's SDL move/resize events through an event watch, so it stays aligned even
 * mid-drag.
 *
 * <p>26.3 (SDL3 + renderpearl): the OpenGL backend renders everything with one context that it makes current on
 * whichever window it presents to, so the overlay is just another GpuSurface of the game's device: present = blit the
 * overlay target to its surface and swap, then hand the context back to the game's window. The Vulkan backend can't do
 * this (its swapchains are always opaque, VK_COMPOSITE_ALPHA_OPAQUE), so StreamProof needs the OpenGL renderer and
 * switches the Graphics API option to it when it's turned on under Vulkan. Exclusive fullscreen would cover the
 * overlay, so fullscreen is borderless fullscreen while this is on (see WindowMixin).
 */
public final class Overlay {
    private static final Minecraft mc = Minecraft.getInstance();

    /** Render3D's output: the overlay target while StreamProof runs, otherwise the main target. */
    public static final LayerTarget OUTPUT = new LayerTarget("prism_streamproof", () -> active() ? Overlay.target : null);

    private static TextureTarget target;
    private static long window;
    private static int windowId, mainId;
    private static GpuSurface surface;
    private static SDL_EventFilter moveWatch;
    private static Boolean openGl;
    private static boolean failed, shown, lastWantBorderless, presenting;
    private static int lastX = Integer.MIN_VALUE, lastY, lastW, lastH;

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
    /** Dev: screenshot name for the overlay target, grabbed at present before it's cleared (no window needed). */
    public static volatile String debugGrabName;
    /** Hidden dev test windows never show the overlay window either (unless a test forces it). */
    private static final boolean HIDDEN_TEST = Boolean.getBoolean("prismglass.hidden");

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

    /** The render target Prism's hidden layer draws into this frame (the main target when StreamProof is off). */
    public static RenderTarget renderTarget() { return OUTPUT.getRenderTarget(); }

    private static PrismWindow mainWindow() { return (PrismWindow) (Object) mc.getWindow(); }

    private static boolean openGl() {
        if (openGl == null) openGl = "OpenGL".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
        return openGl;
    }

    // ---- per frame ---------------------------------------------------------------------------------------

    /** Start of Prism's GUI content (HUD, ClickGUI): everything from here on goes to the hidden layer. */
    public static void markGui() {
        // present() only runs on the OpenGL backend; this runs every frame on both, so Vulkan is caught here
        if (window == 0 && wanted() && !openGl()) requireOpenGl();
        if (active()) ((PrismGuiState) mc.gameRenderer.gameRenderState().guiRenderState).prism$markOverlay();
    }

    /** Render3D.begin: give the overlay the world's depth so non-xray boxes are still hidden behind walls. */
    public static void beginWorld() {
        if (!active()) return;
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        // size changes are applied at the end of the frame (present); for one frame after a resize, skip occlusion
        if (target.width == main.width && target.height == main.height) target.copyDepthFrom(main);
    }

    /** GlSurface.present HEAD (GlDeviceMixin): the game's frame is blitted to its window, which swaps right after. */
    public static void present() {
        if (presenting) return; // the overlay's own surface presents through the same hook
        RenderSystem.assertOnRenderThread();
        enforceBorderless();
        boolean want = wanted();
        boolean wantBorderless = wantsBorderless();
        if (wantBorderless != lastWantBorderless) {
            lastWantBorderless = wantBorderless;
            if (mainWindow().prism$fullscreen()) {
                ((dev.prismglass.mixin.WindowAccessor) (Object) mc.getWindow()).prism$setMode();
                mc.framebufferSizeChanged(); // what vanilla does after its own setMode calls
            }
        }
        if (want && window == 0) create();
        if (!want && window != 0) destroy();
        if (window == 0) return;
        presenting = true;
        try {
            syncWindow();
            if (shown) blit();
            String grab = debugGrabName;
            if (grab != null) {
                debugGrabName = null;
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, grab, target, 1, msg -> Prism.LOG.info("[overlay] {}", msg.getString()));
            }
            clearForNextFrame();
        } catch (Throwable t) {
            fail("overlay error", t);
        } finally {
            presenting = false;
        }
    }

    private static void enforceBorderless() {
        if (!borderless || borderlessRect == null || !mainWindow().prism$fullscreen()) { enforceTries = 0; return; }
        long h = mc.getWindow().handle();
        int[] now = rect(h);
        int[] r = borderlessRect;
        if (now[0] == r[0] && now[1] == r[1] && now[2] == r[2] && now[3] == r[3]) { enforceTries = 0; return; }
        if (enforceTries++ > 20) return; // something else insists; don't fight it forever
        if (Boolean.getBoolean("prismglass.fstest")) Prism.LOG.info("[fstest] enforce try {}: at {},{} {}x{} want {}", enforceTries, now[0], now[1], now[2], now[3], java.util.Arrays.toString(r));
        if ((SDL_GetWindowFlags(h) & SDL_WINDOW_MAXIMIZED) != 0) SDL_RestoreWindow(h);
        SDL_SetWindowSize(h, r[2], r[3]);
        SDL_SetWindowPosition(h, r[0], r[1]);
    }

    private static void clearForNextFrame() {
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height);
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 1.0);
    }

    private static void syncWindow() {
        long main = mc.getWindow().handle();
        long flags = SDL_GetWindowFlags(main);
        // owned by Minecraft's window (other apps cover it like the game), and also hidden whenever the game
        // isn't the focused window, so it can never show over anything else
        boolean visible = debugForceVisible
            || (!HIDDEN_TEST && (flags & SDL_WINDOW_INPUT_FOCUS) != 0 && (flags & (SDL_WINDOW_MINIMIZED | SDL_WINDOW_HIDDEN)) == 0);
        int[] r = rect(main);
        if (r[0] != lastX || r[1] != lastY || r[2] != lastW || r[3] != lastH) {
            lastX = r[0]; lastY = r[1]; lastW = r[2]; lastH = r[3];
            SDL_SetWindowPosition(window, r[0], r[1]);
            SDL_SetWindowSize(window, Math.max(1, r[2]), Math.max(1, r[3]));
        }
        if (visible != shown) {
            // Win32 directly: SW_SHOWNA never activates it, so the game keeps focus
            User32.INSTANCE.ShowWindow(hwnd(window), visible ? SW_SHOWNA : SW_HIDE);
            shown = visible;
        }
    }

    private static void blit() throws Exception {
        // the game's window is current (its frame was just blitted); the backend switches to ours for the blit
        long gameWindow = SDL_GL_GetCurrentWindow(), ctx = SDL_GL_GetCurrentContext();
        try {
            int[] px = pixelSize(window);
            GpuSurface.Configuration cfg = surface.currentConfiguration().orElse(null);
            if (cfg == null || cfg.width() != px[0] || cfg.height() != px[1]) configureSurface(px, gameWindow, ctx);
            surface.acquireNextTexture();
            surface.blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), target.getColorTextureView());
            if (debugReadbackFile != null) readback(px[0], px[1]);
            surface.present();
        } finally {
            // the game's window swaps next, with the context current on it again. GlDevice still thinks ours is
            // current; that only costs it one redundant MakeCurrent when it presents the game's next frame.
            if (gameWindow != 0) SDL_GL_MakeCurrent(gameWindow, ctx);
        }
    }

    private static void configureSurface(int[] px, long gameWindow, long ctx) throws Exception {
        // never wait on the overlay's own vsync
        GpuSurface.PresentMode mode = surface.supportedPresentModes().contains(GpuSurface.PresentMode.IMMEDIATE)
            ? GpuSurface.PresentMode.IMMEDIATE : GpuSurface.PresentMode.FIFO;
        surface.configure(new GpuSurface.Configuration(px[0], px[1], mode));
        // the swap interval should be per window, but some drivers keep it per context: put the game's back
        if (gameWindow != 0) {
            SDL_GL_MakeCurrent(gameWindow, ctx);
            boolean vsync = mc.windowSurface().currentConfiguration().map(c -> c.presentMode() == GpuSurface.PresentMode.FIFO)
                .orElse(mc.options.enableVsync().get());
            SDL_GL_SetSwapInterval(vsync ? 1 : 0);
        }
    }

    // ---- window lifecycle -----------------------------------------------------------------------------------

    private static void create() {
        if (Util.getPlatform() != Util.OS.WINDOWS) { fail("capture exclusion is a Windows feature", null); return; }
        if (!openGl()) { requireOpenGl(); return; }
        long main = mc.getWindow().handle();
        mainId = SDL_GetWindowID(main);
        int[] r = rect(main);

        // the backend's createWindow: same GL attributes (so the same pixel format) as the game's windows, which
        // lets the backend's one context draw to it. Hidden until syncWindow shows it; never focusable; no taskbar.
        long flags = SDL_WINDOW_HIDDEN | SDL_WINDOW_BORDERLESS | SDL_WINDOW_TRANSPARENT | SDL_WINDOW_NOT_FOCUSABLE
            | SDL_WINDOW_UTILITY | SDL_WINDOW_HIGH_PIXEL_DENSITY;
        window = new GlBackend().createWindow("Prism", Math.max(1, r[2]), Math.max(1, r[3]), flags);
        if (window == 0) { fail("couldn't create the overlay window: " + SDLError.SDL_GetError(), null); return; }
        windowId = SDL_GetWindowID(window);

        // can the game's context draw to it, and does its framebuffer have alpha (else it would be opaque black)
        long gameWindow = SDL_GL_GetCurrentWindow(), ctx = SDL_GL_GetCurrentContext();
        boolean current = SDL_GL_MakeCurrent(window, ctx);
        int alpha = -1;
        if (current) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer a = stack.mallocInt(1);
                if (SDL_GL_GetAttribute(SDL_GL_ALPHA_SIZE, a)) alpha = a.get(0);
            }
        }
        if (gameWindow != 0) SDL_GL_MakeCurrent(gameWindow, ctx);
        if (!current) { fail("your GPU driver can't draw to a second window: " + SDLError.SDL_GetError(), null); return; }
        if (alpha == 0) { fail("your GPU driver can't make transparent windows", null); return; }

        Pointer p = hwnd(window);
        // tool window: no taskbar button, not in alt-tab; never takes focus; mouse goes straight through to the game
        // (WS_EX_TRANSPARENT needs WS_EX_LAYERED; a layered window with no colour key/alpha draws as usual)
        long ex = User32.INSTANCE.GetWindowLongPtrW(p, GWL_EXSTYLE);
        User32.INSTANCE.SetWindowLongPtrW(p, GWL_EXSTYLE, (ex | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT | WS_EX_LAYERED) & ~WS_EX_APPWINDOW);
        User32.INSTANCE.SetLayeredWindowAttributes(p, 0, (byte) 0, 0);
        // owned by the game window: always right above it in z-order, hidden when it's minimized
        User32.INSTANCE.SetWindowLongPtrW(p, GWLP_HWNDPARENT, Pointer.nativeValue(hwnd(main)));
        if (!setExcluded(true)) {
            fail("capture exclusion needs Windows 10 (2004) or newer", null);
            return;
        }

        surface = RenderSystem.getDevice().createSurface(window, () -> false);
        RenderTarget m = mc.gameRenderer.mainRenderTarget();
        target = new TextureTarget("prism streamproof", m.width, m.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        clearForNextFrame();
        lastX = Integer.MIN_VALUE;
        shown = false;
        hookMoves();
        Prism.LOG.info("[streamproof] overlay window ready ({}x{})", r[2], r[3]);
    }

    /**
     * Windows runs a modal loop while a window is dragged or resized, so no frames (and no syncWindow) happen until
     * you let go; SDL still pushes the game window's move/resize events, and an event watch sees them right away.
     */
    private static void hookMoves() {
        moveWatch = SDL_EventFilter.create((userdata, ev) -> {
            try {
                SDL_Event e = SDL_Event.create(ev);
                int type = e.type();
                if (window == 0 || (type != SDLEvents.SDL_EVENT_WINDOW_MOVED && type != SDLEvents.SDL_EVENT_WINDOW_RESIZED)) return true;
                SDL_WindowEvent w = e.window();
                if (w.windowID() != mainId) return true;
                if (type == SDLEvents.SDL_EVENT_WINDOW_MOVED) {
                    SDL_SetWindowPosition(window, w.data1(), w.data2());
                    lastX = w.data1();
                    lastY = w.data2();
                } else if (w.data1() > 0 && w.data2() > 0) {
                    SDL_SetWindowSize(window, w.data1(), w.data2());
                    lastW = w.data1();
                    lastH = w.data2();
                }
            } catch (Throwable t) {
                Prism.LOG.error("[streamproof] move watch", t); // never let it unwind into SDL
            }
            return true;
        });
        SDLEvents.SDL_AddEventWatch(moveWatch, 0L);
    }

    private static void unhookMoves() {
        if (moveWatch == null) return;
        SDLEvents.SDL_RemoveEventWatch(moveWatch, 0L);
        moveWatch.free();
        moveWatch = null;
    }

    /** WindowMixin: the overlay's own window events (move, resize, show...) must not reach Minecraft's Window. */
    public static boolean isOverlayEvent(SDL_Event event) {
        // by ID, not handle: events still queued when the window is destroyed are filtered too (SDL never reuses IDs)
        if (windowId == 0) return false;
        int type = event.type();
        return type >= SDLEvents.SDL_EVENT_WINDOW_FIRST && type <= SDLEvents.SDL_EVENT_WINDOW_LAST && event.window().windowID() == windowId;
    }

    private static void destroy() {
        unhookMoves();
        if (surface != null) {
            if (!surface.isAcquired()) surface.close(); // an acquired one (error mid-blit) holds nothing native on GL
            surface = null;
        }
        if (window != 0) SDL_DestroyWindow(window);
        window = 0;
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

    /** Vulkan backend: switch the Graphics API option to OpenGL (applies on restart) and say so. */
    private static void requireOpenGl() {
        if (mc.options.preferredGraphicsBackend().get() != PreferredGraphicsApi.OPENGL) {
            mc.options.preferredGraphicsBackend().set(PreferredGraphicsApi.OPENGL);
            mc.options.save();
        }
        fail("it needs the OpenGL renderer (Vulkan windows can't be transparent). Graphics API is now OpenGL: restart Minecraft and turn it on again", null);
    }

    /** WDA_EXCLUDEFROMCAPTURE on/off; the dev test turns it off once to prove the overlay itself composites. */
    public static boolean setExcluded(boolean excluded) {
        if (window == 0) return false;
        return User32.INSTANCE.SetWindowDisplayAffinity(hwnd(window), excluded ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE);
    }

    public static int affinity() {
        if (window == 0) return -1;
        IntByReference out = new IntByReference();
        User32.INSTANCE.GetWindowDisplayAffinity(hwnd(window), out);
        return out.getValue();
    }

    public static boolean isShown() { return shown; }

    /** Dev test: Win32's view of an SDL window (outer rect, maximized state). */
    public static String debugWin32(long sdlWindow) {
        Pointer p = hwnd(sdlWindow);
        int[] r = new int[4];
        User32.INSTANCE.GetWindowRect(p, r);
        return "rect=" + r[0] + "," + r[1] + "-" + r[2] + "," + r[3] + " zoomed=" + User32.INSTANCE.IsZoomed(p);
    }

    /** Dev test: put the overlay back on top of the (test-floated) game window. */
    public static void debugRaise() {
        if (window == 0) return;
        SDL_SetWindowAlwaysOnTop(window, false);
        SDL_SetWindowAlwaysOnTop(window, true);
    }

    // ---- SDL helpers ------------------------------------------------------------------------------------------

    /** {x, y, width, height} of an SDL window, in SDL's window coordinates. */
    public static int[] rect(long sdlWindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), w = stack.mallocInt(1), h = stack.mallocInt(1);
            SDL_GetWindowPosition(sdlWindow, x, y);
            SDL_GetWindowSize(sdlWindow, w, h);
            return new int[]{x.get(0), y.get(0), w.get(0), h.get(0)};
        }
    }

    /** {width, height} of an SDL window's drawable, in pixels. */
    public static int[] pixelSize(long sdlWindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            SDL_GetWindowSizeInPixels(sdlWindow, w, h);
            return new int[]{Math.max(1, w.get(0)), Math.max(1, h.get(0))};
        }
    }

    /** The Win32 HWND behind an SDL window. */
    private static Pointer hwnd(long sdlWindow) {
        return new Pointer(SDLProperties.SDL_GetPointerProperty(SDL_GetWindowProperties(sdlWindow), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L));
    }

    // ---- GL bits ---------------------------------------------------------------------------------------------

    /** Dev only: saves what the overlay window's own framebuffer holds (it's current right after the blit). */
    private static void readback(int w, int h) {
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
        // raw GL behind the backend's back: put every binding we touch back exactly as it was
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int prevPack = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int prevAlign = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, 0);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
            GL11C.glReadBuffer(GL11C.GL_BACK);
            GL11C.glReadPixels(0, 0, w, h, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf);
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
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, prevPack);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, prevAlign);
            org.lwjgl.system.MemoryUtil.memFree(buf);
            debugReadbackFile = null;
        }
    }

    // ---- Win32 -------------------------------------------------------------------------------------------------

    private static final int GWL_EXSTYLE = -20, GWLP_HWNDPARENT = -8;
    private static final long WS_EX_TOOLWINDOW = 0x00000080L, WS_EX_NOACTIVATE = 0x08000000L, WS_EX_APPWINDOW = 0x00040000L,
        WS_EX_TRANSPARENT = 0x00000020L, WS_EX_LAYERED = 0x00080000L;
    private static final int WDA_NONE = 0x0, WDA_EXCLUDEFROMCAPTURE = 0x11;
    private static final int SW_HIDE = 0, SW_SHOWNA = 8;

    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);

        boolean SetWindowDisplayAffinity(Pointer hwnd, int affinity);

        boolean GetWindowDisplayAffinity(Pointer hwnd, IntByReference affinity);

        long GetWindowLongPtrW(Pointer hwnd, int index);

        boolean GetWindowRect(Pointer hwnd, int[] rect);

        boolean IsZoomed(Pointer hwnd);

        long SetWindowLongPtrW(Pointer hwnd, int index, long value);

        boolean SetLayeredWindowAttributes(Pointer hwnd, int colorKey, byte alpha, int flags);

        boolean ShowWindow(Pointer hwnd, int cmdShow);
    }
}
