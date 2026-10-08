package dev.prismglass.module.client;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.KeySetting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.InvUtil;
import dev.prismglass.util.KeyUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * Keybinds for things that aren't modules (ClickGUI top bar → Keybinds): Inspect, QuickPearl, coords, waypoints...
 * Lives as an always-on hidden module so the binds save with the profile.
 */
public class KeyActions extends Module {
    public final KeySetting inspect = key("Inspect", GLFW.GLFW_KEY_I, "CS:GO-style inspect animation of the item in your hand.");
    public final KeySetting quickPearl = key("QuickPearl", KeyUtil.NONE, "Throw an ender pearl from your hotbar without switching to it.");
    public final KeySetting markWaypoint = key("MarkWaypoint", KeyUtil.NONE, "Drop a waypoint where you stand.");
    public final KeySetting copyCoords = key("CopyCoords", KeyUtil.NONE, "Copy your coordinates.");
    public final KeySetting hudEditor = key("HudEditor", KeyUtil.NONE, "Open the HUD editor.");
    public final KeySetting panic = key("Panic", KeyUtil.NONE, "Turn every module off.");

    /** One bindable action: its key and what it does. */
    public record Action(String name, String description, KeySetting key, Runnable run) {}

    private final List<Action> actions = new ArrayList<>();
    private static long inspectStart = -1;
    private int pearlRestore = -1, marks;

    public KeyActions() {
        super("Keybinds", "Binds for non-module actions (ClickGUI → Keybinds).", Category.CLIENT);
        drawn.set(false);
        setEnabledSilently(true);
        actions.add(new Action("Inspect", "CS:GO-style inspect of your held item", inspect, () -> inspectStart = System.nanoTime()));
        actions.add(new Action("Quick Pearl", "Throw a pearl without switching to it", quickPearl, this::throwPearl));
        actions.add(new Action("Mark Waypoint", "Waypoint where you stand", markWaypoint, () -> {
            var wp = Prism.modules().get(dev.prismglass.module.render.Waypoints.class);
            String name = "Mark " + (++marks);
            wp.add(name, mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
            if (!wp.isEnabled()) wp.setEnabled(true);
            ChatUtil.good("Waypoint " + name);
        }));
        actions.add(new Action("Copy Coords", "Copy your coordinates", copyCoords, () -> {
            mc.keyboardHandler.setClipboard(mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ());
            ChatUtil.good("Coordinates copied");
        }));
        actions.add(new Action("HUD Editor", "Open the HUD editor", hudEditor, () -> mc.setScreen(new dev.prismglass.gui.HudEditorScreen(null))));
        actions.add(new Action("Panic", "Turn every module off", panic, () -> {
            for (Module m : Prism.modules().all()) if (m.isEnabled() && m != this && !(m instanceof AntiCheat) && !(m instanceof Hud)) m.setEnabled(false);
            ChatUtil.info("Panic: everything off");
        }));
    }

    public List<Action> actions() { return actions; }

    @Override public void toggle() { /* always on */ }

    /** Key pressed in game (not in a screen). */
    public void onKeyPress(int key) {
        if (key == KeyUtil.NONE || mc.player == null || mc.screen != null) return;
        for (Action a : actions) if (a.key().get() == key) a.run().run();
    }

    private void throwPearl() {
        var p = mc.player;
        if (p.getOffhandItem().is(Items.ENDER_PEARL)) { mc.gameMode.useItem(p, InteractionHand.OFF_HAND); return; }
        int slot = InvUtil.findHotbar(Items.ENDER_PEARL);
        if (slot == -1) { ChatUtil.error("No pearls in your hotbar"); return; }
        if (p.getCooldowns().isOnCooldown(p.getInventory().getItem(slot))) return;
        int prev = p.getInventory().getSelectedSlot();
        if (slot != prev) {
            if (!Prism.guard().canSwitchSlot()) return;
            InvUtil.swap(slot, false);
            pearlRestore = prev;
        }
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    @Override
    public void onTick() {
        if (pearlRestore != -1) { InvUtil.restore(pearlRestore); pearlRestore = -1; }
    }

    // ---- Inspect ----------------------------------------------------------------------------------------

    private static final float INSPECT_SECONDS = 2.4f;
    /** Keyframes: time, translate x/y/z (toward the centre for the right hand), rotate x/y/z degrees. */
    private static final float[][] KEYS = {
        {0.00f, 0f, 0f, 0f, 0f, 0f, 0f},
        {0.16f, -0.20f, 0.10f, 0.14f, 10f, -55f, 18f},   // bring it in and turn it to show the side
        {0.42f, -0.14f, 0.15f, 0.12f, -28f, 70f, -12f},  // roll it over to the other side
        {0.70f, -0.10f, 0.08f, 0.06f, 18f, 80f, 28f},    // tilt it back, admire
        {1.00f, 0f, 0f, 0f, 0f, 0f, 0f},                 // and back down
    };

    /** The inspect pose right now, or null when not inspecting: {tx, ty, tz, rx, ry, rz}. */
    public static float[] inspectPose() {
        if (inspectStart < 0) return null;
        float t = (System.nanoTime() - inspectStart) / 1e9f / INSPECT_SECONDS;
        if (t >= 1f) { inspectStart = -1; return null; }
        int i = 0;
        while (i < KEYS.length - 2 && t > KEYS[i + 1][0]) i++;
        float[] a = KEYS[i], b = KEYS[i + 1];
        float f = (t - a[0]) / (b[0] - a[0]);
        f = f * f * (3 - 2 * f); // ease in and out between keyframes
        float[] out = new float[6];
        for (int k = 0; k < 6; k++) out[k] = Mth.lerp(f, a[k + 1], b[k + 1]);
        return out;
    }
}
