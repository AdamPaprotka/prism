package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.client.StreamProof;
import dev.prismglass.module.render.ESP;
import dev.prismglass.streamproof.Overlay;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.glfw.GLFW;

/**
 * Dev-only StreamProof test (./gradlew runClient -Pstreamtest). Flat world, a zombie with ESP box + tracer, StreamProof
 * on. Logs "[streamtest] CAPTURE n x y w h" at moments an external screen capture should be taken:
 * 1 = exclusion on (capture must show vanilla only), 2 = exclusion off (capture must show the overlay composited),
 * 3 = borderless fullscreen with exclusion on. Also saves what the overlay window itself shows (glReadPixels).
 */
public final class StreamTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t;
    private static boolean requested;

    private StreamTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[streamtest] {}", s); }

    private static void tick() {
        ticks++;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismstream-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(), WorldPresets::createFlatWorldDimensions, null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        t++;
        MinecraftServer server = mc.getSingleplayerServer();
        long main = mc.getWindow().handle();

        if (t == 40) {
            run(server, "gamerule advance_time false");
            run(server, "time set day");
            run(server, "kill @e[type=!player]");
            run(server, "execute at @p run summon zombie ~6 ~ ~2 {NoAI:1b,PersistenceRequired:1b}");
        }
        if (t == 50) run(server, "execute as @p at @p run tp @p ~ ~ ~ facing entity @e[type=zombie,limit=1] eyes");
        if (t == 60) {
            ESP esp = Prism.modules().get(ESP.class);
            esp.mode.parse("Both");
            esp.setEnabled(true);
            if (!Boolean.getBoolean("prismglass.streamtest.nosp")) Prism.modules().get(StreamProof.class).setEnabled(true);
            Overlay.debugForceVisible = true;
            // keep Minecraft above other windows during the test so the captures show it (the owned overlay stays above it)
            GLFW.glfwSetWindowAttrib(main, GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
            GLFW.glfwFocusWindow(main);
        }
        if (t == 100) {
            log("overlay active=" + Overlay.active() + " shown=" + Overlay.isShown() + " affinity=0x" + Integer.toHexString(Overlay.affinity()));
            Overlay.debugRaise();
            Overlay.debugReadbackFile = mc.gameDirectory.toPath().resolve("streamproof-overlay.png").toString();
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-stream-main.png", mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        }
        if (t == 120) capture(1);
        if (t == 400) {
            log("exclusion off: " + Overlay.setExcluded(false) + " affinity=0x" + Integer.toHexString(Overlay.affinity()));
        }
        if (t == 420) capture(2);
        if (t == 440) {
            mc.setScreen(new dev.prismglass.gui.ClickGuiScreen());
            dev.prismglass.util.ChatUtil.good("StreamProof test message (should be a hidden toast)");
        }
        if (t == 470) {
            log("clickgui cursor mode=0x" + Integer.toHexString(GLFW.glfwGetInputMode(main, GLFW.GLFW_CURSOR)) + " (hidden=0x34002)");
            Overlay.debugReadbackFile = mc.gameDirectory.toPath().resolve("streamproof-clickgui.png").toString();
        }
        if (t == 480) capture(4);
        if (t == 520) mc.setScreen(null);
        if (t == 530) log("after close cursor mode=0x" + Integer.toHexString(GLFW.glfwGetInputMode(main, GLFW.GLFW_CURSOR)) + " (disabled=0x34003)");
        if (t == 700) {
            Overlay.setExcluded(true);
            // like real play: not forced on top, overlay visibility from focus as normal
            GLFW.glfwSetWindowAttrib(main, GLFW.GLFW_FLOATING, GLFW.GLFW_FALSE);
            Overlay.debugForceVisible = false;
            GLFW.glfwFocusWindow(main);
            mc.getWindow().toggleFullScreen();
        }
        if (t == 760) {
            long monitor = GLFW.glfwGetWindowMonitor(main);
            int[] w = new int[1], h = new int[1];
            GLFW.glfwGetWindowSize(main, w, h);
            log("fullscreen=" + mc.getWindow().isFullscreen() + " exclusiveMonitor=" + monitor + " decorated=" + GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_DECORATED)
                + " size=" + w[0] + "x" + h[0] + " overlayShown=" + Overlay.isShown() + " affinity=0x" + Integer.toHexString(Overlay.affinity()));
            Overlay.debugRaise();
            Overlay.debugReadbackFile = mc.gameDirectory.toPath().resolve("streamproof-overlay-fs.png").toString();
        }
        if (t == 780) capture(3);
        if (t == 900) {
            GLFW.glfwFocusWindow(main);
            log("fullscreen exclusion off: " + Overlay.setExcluded(false) + " focused=" + GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_FOCUSED)
                + " overlayShown=" + Overlay.isShown());
        }
        if (t == 920) capture(5);
        if (t == 1060) mc.getWindow().toggleFullScreen();
        if (t == 1100) {
            log("back to windowed: fullscreen=" + mc.getWindow().isFullscreen() + " decorated=" + GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_DECORATED));
            GLFW.glfwSetWindowAttrib(main, GLFW.GLFW_FLOATING, GLFW.GLFW_FALSE);
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }

    private static void capture(int n) {
        long main = mc.getWindow().handle();
        int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
        GLFW.glfwGetWindowPos(main, x, y);
        GLFW.glfwGetWindowSize(main, w, h);
        log("CAPTURE " + n + " " + x[0] + " " + y[0] + " " + w[0] + " " + h[0]);
    }

    private static void run(MinecraftServer server, String cmd) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }
}
