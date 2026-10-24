package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.gui.AboutScreen;
import dev.prismglass.gui.ClickGuiScreen;
import dev.prismglass.gui.render.Holo;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.setting.Setting;
import java.lang.reflect.Method;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import com.mojang.blaze3d.platform.InputConstants;
import dev.prismglass.util.KeyUtil;

/**
 * Dev only (-Pholotest): ClickGUI PopOut hologram. Opens the GUI in a flat world, aims the crosshair at rows of the
 * floating panel and clicks/drags through the real MouseHandler callbacks (the OS mouse position is ignored), checks
 * head steering, that the panel stays put while you turn and walk, off-panel clicks, that closing gives the mouse
 * back, and the flat fallback when the world hook doesn't run. -Pholosp runs it all with StreamProof on. Logs "[holotest]", screenshots
 * prism-holo-*.png, exits.
 */
public final class HoloTest {
    private static double heldErr;
    private static double maxTurnLeft;
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t, passed, failed;
    private static boolean requested;
    private static Module mod;
    private static BoolSetting bool;
    private static NumberSetting num;
    private static boolean modBefore, boolBefore;
    private static double numBefore;
    private static float yawBefore;
    private static net.minecraft.world.phys.Vec3 anchorAt;
    /** -Pholosp: same run with StreamProof on (panel drawn into the hidden overlay). */
    private static final boolean SP = Boolean.getBoolean("prismglass.holosp");

    private HoloTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[holotest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void shot(String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
    }

    private static void invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method m = MouseHandler.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            m.invoke(mc.mouseHandler, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** A real mouse button callback (what clicking does). */
    private static void button(int button, int action) {
        invoke("onButton", new Class<?>[]{long.class, MouseButtonInfo.class, int.class}, mc.getWindow().handle(), new MouseButtonInfo(button, 0), action);
    }

    private static void click(int button) {
        button(button, InputConstants.PRESS);
        button(button, InputConstants.RELEASE);
    }

    private static double lastMoveX = Double.NaN, lastMoveY;

    /** SDL motion events carry the relative delta too; derive it from the previous synthetic position. */
    private static void move(double x, double y) {
        double dx = Double.isNaN(lastMoveX) ? 0 : x - lastMoveX, dy = Double.isNaN(lastMoveX) ? 0 : y - lastMoveY;
        lastMoveX = x; lastMoveY = y;
        invoke("onMove", new Class<?>[]{long.class, double.class, double.class, double.class, double.class}, mc.getWindow().handle(), x, y, dx, dy);
    }

    private static ClickGuiScreen gui() { return mc.gui.screen() instanceof ClickGuiScreen g ? g : null; }

    /** Turn the head so the crosshair sits on GUI point (gx, gy) of the panel. */
    private static void aim(double gx, double gy) {
        float[] yp = Holo.lookAt(gx, gy);
        mc.player.setYRot(yp[0]);
        mc.player.setXRot(yp[1]);
        mc.player.yRotO = yp[0];
        mc.player.xRotO = yp[1];
    }

    private static void aimAt(float[] r, double fx) { aim(r[0] + r[2] * fx, r[1] + r[3] * 0.5); }

    private static boolean onRect(float[] r) {
        double[] c = gui().debugHoloCursor();
        return c[0] >= r[0] && c[0] < r[0] + r[2] && c[1] >= r[1] && c[1] < r[1] + r[3];
    }

    private static String cursor() {
        double[] c = gui().debugHoloCursor();
        return String.format(Locale.ROOT, "%.1f,%.1f", c[0], c[1]);
    }

    private static final int CURSOR_NORMAL = 0, CURSOR_HIDDEN = 1, CURSOR_DISABLED = 2; // the old GLFW_CURSOR modes, from SDL state
    private static int cursorMode() {
        return KeyUtil.cursorLocked(mc.getWindow()) ? CURSOR_DISABLED : KeyUtil.cursorHidden() ? CURSOR_HIDDEN : CURSOR_NORMAL;
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismholo-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        ClickGui theme = Prism.modules().get(ClickGui.class);

        if (t == 20) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            run(s, "gamerule advance_time false");
            run(s, "time set day");
            run(s, "tp @p 0.5 -60 0.5 0 0");
            theme.popOut.set(true);
            theme.holoSway.set(false); // deterministic aim; sway is a few cm
            theme.blur.set(true);      // must be skipped while popped out
            if (SP) Prism.modules().get(dev.prismglass.module.client.StreamProof.class).setEnabled(true);
        }
        if (t == 30) {
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            mc.player.setYBodyRot(0);
            mc.player.yBodyRotO = 0;
            theme.setEnabled(true); // opens the ClickGUI like the bind does
        }
        if (t == 45) {
            check("ClickGUI open with PopOut active", gui() != null && Holo.active());
            check("OS cursor locked while popped out", cursorMode() == CURSOR_DISABLED);
            check("capture target exists, panel live", Holo.target != null && Holo.live());
            if (SP) {
                check("StreamProof overlay active", dev.prismglass.streamproof.Overlay.active());
                shot("prism-holo-sp-main.png"); // what capture sees
                dev.prismglass.streamproof.Overlay.debugGrabName = "prism-holo-sp-overlay.png"; // the hidden layer
            } else shot("prism-holo-open.png");
            // a module with a toggle and a slider, in a panel row we can see
            if (gui() != null) {
                for (Category c : new Category[]{Category.MOVEMENT, Category.PLAYER, Category.RENDER, Category.WORLD}) {
                    for (Module m : Prism.modules().byCategory(c)) {
                        if (gui().debugRect(m) == null) continue;
                        boolean hasB = false, hasN = false;
                        for (Setting<?> st : m.getSettings()) {
                            if (st instanceof BoolSetting && st.isVisible()) hasB = true;
                            if (st instanceof NumberSetting && st.isVisible()) hasN = true;
                        }
                        if (hasB && hasN) { mod = m; break; }
                    }
                    if (mod != null) break;
                }
            }
            log("target module " + (mod == null ? "none" : mod.getName() + " rect " + java.util.Arrays.toString(gui().debugRect(mod))));
            if (mod == null) { check("found a target module", false); t = 199; return; }
        }
        if (t > 45 && t < 118 && gui() == null) { check("GUI still open at t=" + t, false); t = 199; return; }
        if (t == 50) aimAt(gui().debugRect(mod), 0.3);
        if (t == 53) {
            check("crosshair maps onto the module row (cursor " + cursor() + ")", onRect(gui().debugRect(mod)));
            modBefore = mod.isEnabled();
            click(InputConstants.MOUSE_BUTTON_LEFT);
        }
        if (t == 55) check("left click through the hologram toggled " + mod.getName(), mod.isEnabled() != modBefore);
        if (t == 58) shot("prism-holo-click.png");
        if (t == 60) click(InputConstants.MOUSE_BUTTON_RIGHT); // expand settings
        if (t == 75) {
            for (Setting<?> st : mod.getSettings()) {
                if (!st.isVisible() || gui().debugRect(st) == null) continue;
                if (bool == null && st instanceof BoolSetting b) bool = b;
                if (num == null && st instanceof NumberSetting n) num = n;
            }
            check("right click expanded the settings (bool " + (bool == null ? "-" : bool.getName()) + ", slider " + (num == null ? "-" : num.getName()) + ")", bool != null && num != null);
            if (bool != null) aimAt(gui().debugRect(bool), 0.5);
        }
        if (t == 78 && bool != null) {
            boolBefore = bool.get();
            click(InputConstants.MOUSE_BUTTON_LEFT);
        }
        if (t == 80 && bool != null) check("bool setting " + bool.getName() + " toggled via hologram", bool.get() != boolBefore);
        if (t == 82 && num != null) aimAt(gui().debugRect(num), 0.15);
        if (t == 85 && num != null) {
            numBefore = num.get();
            button(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.PRESS); // grab the slider near its left end
        }
        if (t == 87 && num != null) aimAt(gui().debugRect(num), 0.85); // sweep the head right while holding
        if (t == 90 && num != null) {
            double mid = num.get();
            button(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.RELEASE);
            double lo = num.getMin() + (num.getMax() - num.getMin()) * 0.6;
            check(String.format(Locale.ROOT, "slider %s dragged by head movement (%.3f -> %.3f, want > %.3f)", num.getName(), numBefore, mid, lo), mid > lo);
            shot("prism-holo-settings.png");
        }
        if (t == 95) aim(gui().width * 0.5, gui().height * 0.5);
        if (t == 97) {
            yawBefore = mc.player.getYRot();
            move(1000, 500); // first event only primes the delta
            move(1100, 500);
        }
        if (t == 99) check(String.format(Locale.ROOT, "locked mouse turns the head (yaw %.1f -> %.1f)", yawBefore, mc.player.getYRot()), mc.player.getYRot() > yawBefore + 1);
        if (t == 100) {
            anchorAt = Holo.base();
            mc.player.setYRot(mc.player.getYRot() + 120); // turn far away: the body turns too, the panel must not
            mc.player.yRotO = mc.player.getYRot();
        }
        if (t == 104) {
            check("turning away: panel stays where it was placed (moved " + String.format(Locale.ROOT, "%.4f", Holo.base().distanceTo(anchorAt)) + ")",
                Holo.base().distanceTo(anchorAt) < 1e-9);
            check("crosshair off the panel -> no cursor (" + cursor() + ")", Double.isNaN(gui().debugHoloCursor()[0]));
            boolean before = mod.isEnabled();
            click(InputConstants.MOUSE_BUTTON_LEFT);
            check("clicks off the panel do nothing", mod.isEnabled() == before && gui() != null);
            shot("prism-holo-away.png");
        }
        if (t == 105) run(s, "tp @p 3.5 -60 -1.5"); // walk away (to the side and back)
        if (t >= 105 && t <= 110) maxTurnLeft = Math.max(maxTurnLeft, Holo.yawErrorDeg());
        if (t == 110) {
            check("walking: panel stays in the world (moved " + String.format(Locale.ROOT, "%.4f", Holo.base().distanceTo(anchorAt)) + ")",
                Holo.base().distanceTo(anchorAt) < 1e-9 && mc.player.getX() > 3);
            aim(gui().width * 0.5, gui().height * 0.5); // look back at it from the new spot
        }
        if (t == 113) {
            double[] c = gui().debugHoloCursor();
            check("looking back at it from elsewhere still hits the middle (" + cursor() + ")",
                Math.abs(c[0] - gui().width * 0.5) < 2 && Math.abs(c[1] - gui().height * 0.5) < 2);
            shot("prism-holo-walked.png");
        }
        if (t == 117) {
            // HoloFace: the panel turned (yaw) to face where we now stand
            net.minecraft.world.phys.Vec3 toMe = mc.player.getEyePosition().subtract(Holo.base());
            net.minecraft.world.phys.Vec3 flat = new net.minecraft.world.phys.Vec3(toMe.x, 0, toMe.z).normalize();
            net.minecraft.world.phys.Vec3 tw = Holo.toward();
            double dot = new net.minecraft.world.phys.Vec3(tw.x, 0, tw.z).normalize().dot(flat);
            check("HoloFace: after walking aside the panel faces us (dot " + String.format(Locale.ROOT, "%.3f", dot) + ")", dot > 0.98);
            check("HoloFace tweens: it was still turning right after the step (max " + String.format(Locale.ROOT, "%.1f", maxTurnLeft) + " deg left)", maxTurnLeft > 3);
        }
        if (t == 118) mc.gui.setScreen(new AboutScreen(gui())); // close into another screen
        if (t == 121) {
            check("closing into another screen unlocks the mouse", cursorMode() == CURSOR_NORMAL);
            check("hologram inactive outside the ClickGUI", !Holo.active() && !Holo.steering());
            float yaw = mc.player.getYRot();
            move(1200, 500);
            move(1300, 500);
            check("mouse no longer turns the head", mc.player.getYRot() == yaw);
            mc.gui.setScreen(new ClickGuiScreen());
        }
        if (t == 122) { Holo.testHold = true; run(s, "tp @p -3.5 -60 -1.5"); } // step aside while holding Shift
        if (t == 127) {
            heldErr = Holo.yawErrorDeg();
            check("Shift held: the panel does not turn toward us (" + String.format(Locale.ROOT, "%.1f", heldErr) + " deg off)", heldErr > 10);
            Holo.testHold = false;
        }
        if (t == 128) {
            check("Shift released: it turns again (" + String.format(Locale.ROOT, "%.1f -> %.1f", heldErr, Holo.yawErrorDeg()) + " deg)", Holo.yawErrorDeg() < heldErr - 1);
            check("reopened: popped out again", Holo.live() && cursorMode() == CURSOR_DISABLED);
            mc.gui.setScreen(null); // close into the game
        }
        if (t == 132) {
            check("closed into the game: no hologram, screen null", mc.gui.screen() == null && !Holo.active());
            boolean grabbed = mc.mouseHandler.isMouseGrabbed();
            log("window active " + mc.isWindowActive() + ", vanilla grabbed " + grabbed);
            check("mouse handed back to vanilla (cursor mode matches vanilla's grab state)",
                cursorMode() == (grabbed ? CURSOR_DISABLED : CURSOR_NORMAL));
            shot("prism-holo-closed.png");
            if (mod.isEnabled() != modBefore) mod.toggle();
            if (bool != null && bool.get() != boolBefore) bool.toggle();
            if (num != null) num.set(numBefore);
        }
        // fallback: the world hook doesn't run (some other mod in the way) -> normal flat ClickGUI, free mouse
        if (t == 140) {
            Holo.debugNoWorld = true;
            mc.gui.setScreen(new ClickGuiScreen());
        }
        if (t == 148) {
            check("no world hook: flat ClickGUI, not popped out", gui() != null && !Holo.live() && !Holo.capturing() && !Holo.steering());
            check("no world hook: mouse stays free (not locked)", cursorMode() != CURSOR_DISABLED);
            check("no world hook: no hologram cursor", Double.isNaN(gui().debugHoloCursor()[0]));
            shot("prism-holo-fallback.png");
        }
        if (t == 152) {
            mc.gui.setScreen(null);
            Holo.debugNoWorld = false;
            theme.popOut.set(false);
            theme.holoSway.set(true);
            theme.blur.set(theme.blur.getDefault());
            if (SP) Prism.modules().get(dev.prismglass.module.client.StreamProof.class).setEnabled(false);
        }
        if (t == 200) {
            log("END passed=" + passed + " failed=" + failed);
            Runtime.getRuntime().halt(0);
        }
    }
}
