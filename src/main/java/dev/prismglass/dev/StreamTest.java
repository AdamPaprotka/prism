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
import dev.prismglass.streamproof.PrismWindow;
import dev.prismglass.util.KeyUtil;
import org.lwjgl.sdl.SDLVideo;

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
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(), r -> r.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
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
            SDLVideo.SDL_SetWindowAlwaysOnTop(main, true);
            SDLVideo.SDL_RaiseWindow(main);
        }
        if (t == 100) {
            log("overlay active=" + Overlay.active() + " shown=" + Overlay.isShown() + " affinity=0x" + Integer.toHexString(Overlay.affinity()));
            Overlay.debugRaise();
            Overlay.debugReadbackFile = mc.gameDirectory.toPath().resolve("streamproof-overlay.png").toString();
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-stream-main.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
        }
        if (t == 120) capture(1);
        if (t == 400) {
            log("exclusion off: " + Overlay.setExcluded(false) + " affinity=0x" + Integer.toHexString(Overlay.affinity()));
        }
        if (t == 420) capture(2);
        if (t == 440) {
            mc.gui.setScreen(new dev.prismglass.gui.ClickGuiScreen());
            dev.prismglass.util.ChatUtil.good("StreamProof test message (should be a hidden toast)");
        }
        if (t == 470) {
            log("clickgui cursor hidden=" + KeyUtil.cursorHidden() + " locked=" + KeyUtil.cursorLocked(mc.getWindow()) + " (want hidden=true locked=false)");
            Overlay.debugReadbackFile = mc.gameDirectory.toPath().resolve("streamproof-clickgui.png").toString();
        }
        if (t == 480) capture(4);
        if (t == 520) mc.gui.setScreen(null);
        if (t == 530) log("after close cursor locked=" + KeyUtil.cursorLocked(mc.getWindow()) + " (want locked=true)");
        if (t == 700) {
            Overlay.setExcluded(true);
            // like real play: not forced on top, overlay visibility from focus as normal
            SDLVideo.SDL_SetWindowAlwaysOnTop(main, false);
            Overlay.debugForceVisible = false;
            SDLVideo.SDL_RaiseWindow(main);
            toggleFullscreen();
        }
        if (t == 760) {
            int[] r = Overlay.rect(main);
            log("fullscreen=" + fullscreen() + " exclusive=" + mc.getWindow().isExclusiveFullscreen() + " decorated=" + decorated(main)
                + " size=" + r[2] + "x" + r[3] + " overlayShown=" + Overlay.isShown() + " affinity=0x" + Integer.toHexString(Overlay.affinity()));
            Overlay.debugRaise();
            Overlay.debugReadbackFile = mc.gameDirectory.toPath().resolve("streamproof-overlay-fs.png").toString();
        }
        if (t == 780) capture(3);
        if (t == 900) {
            SDLVideo.SDL_RaiseWindow(main);
            log("fullscreen exclusion off: " + Overlay.setExcluded(false) + " focused=" + ((SDLVideo.SDL_GetWindowFlags(main) & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0)
                + " overlayShown=" + Overlay.isShown());
        }
        if (t == 920) capture(5);
        if (t == 1060) toggleFullscreen();
        if (t == 1100) {
            log("back to windowed: fullscreen=" + fullscreen() + " decorated=" + decorated(main));
            SDLVideo.SDL_SetWindowAlwaysOnTop(main, false);
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }

    private static void capture(int n) {
        int[] r = Overlay.rect(mc.getWindow().handle());
        log("CAPTURE " + n + " " + r[0] + " " + r[1] + " " + r[2] + " " + r[3]);
    }

    /** F11: 26.3's Window has no toggle; the option applies it on the render thread (updateFullscreenIfChanged). */
    private static void toggleFullscreen() { mc.options.fullscreen().set(!mc.options.fullscreen().get()); }

    private static boolean fullscreen() { return ((PrismWindow) (Object) mc.getWindow()).prism$fullscreen(); }

    private static boolean decorated(long window) { return (SDLVideo.SDL_GetWindowFlags(window) & SDLVideo.SDL_WINDOW_BORDERLESS) == 0; }

    private static void run(MinecraftServer server, String cmd) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }
}
