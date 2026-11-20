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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Keybinds for things that aren't modules (ClickGUI top bar → Keybinds): Inspect, QuickPearl, coords, waypoints...
 * Lives as an always-on hidden module so the binds save with the profile.
 */
public class KeyActions extends Module {
    public final KeySetting inspect = key("Inspect", InputConstants.KEY_I, "CS:GO-style inspect animation of the item in your hand.");
    public final KeySetting quickPearl = key("QuickPearl", KeyUtil.NONE, "Throw an ender pearl from your hotbar without switching to it.");
    public final KeySetting markWaypoint = key("MarkWaypoint", KeyUtil.NONE, "Drop a waypoint where you stand.");
    public final KeySetting copyCoords = key("CopyCoords", KeyUtil.NONE, "Copy your coordinates.");
    public final KeySetting hudEditor = key("HudEditor", KeyUtil.NONE, "Open the HUD editor.");
    public final KeySetting panic = key("Panic", KeyUtil.NONE, "Turn every module off.");

    /** One bindable action: its key and what it does. */
    public record Action(String name, String description, KeySetting key, Runnable run) {}

    private final List<Action> actions = new ArrayList<>();
    private int pearlRestore = -1, marks;

    public KeyActions() {
        super("Keybinds", "Binds for non-module actions (ClickGUI → Keybinds).", Category.CLIENT);
        drawn.set(false);
        setEnabledSilently(true);
        actions.add(new Action("Inspect", "CS:GO-style inspect of your held item (slowed by mining fatigue and FatigueAnim)", inspect,
            () -> Prism.modules().get(dev.prismglass.module.render.Inspect.class).trigger()));
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
        actions.add(new Action("HUD Editor", "Open the HUD editor", hudEditor, () -> mc.gui.setScreen(new dev.prismglass.gui.HudEditorScreen(null))));
        actions.add(new Action("Panic", "Turn every module off", panic, () -> {
            panic();
            ChatUtil.info("Panic: everything off");
        }));
    }

    /** Built-in actions followed by one row per macro (prism/macros.json). */
    public List<Action> actions() {
        if (Prism.macros() == null) return actions;
        List<Action> all = new ArrayList<>(actions);
        for (var m : Prism.macros().all())
            all.add(new Action("Macro: " + m.name, m.lines.size() + " line(s) - edit with .macro", m.key, () -> Prism.macros().run(m.name)));
        return all;
    }

    /** Turns every module off except the client plumbing (AntiCheat, HUD, services, internal ones). */
    public static void panic() { panic(null); }

    /** Panic that leaves {@code keep} on (StaffWatcher's AutoLog keeps watching). */
    public static void panic(Module keep) {
        for (Module m : Prism.modules().all())
            if (m.isEnabled() && m != keep && !m.internal() && !(m instanceof AntiCheat) && !(m instanceof Hud) && !(m instanceof dev.prismglass.module.Service)) m.setEnabled(false);
    }

    @Override public boolean internal() { return true; }

    @Override public void toggle() { /* always on */ }

    /** Key pressed in game (not in a screen). */
    public void onKeyPress(int key) {
        if (key == KeyUtil.NONE || mc.player == null || mc.gui.screen() != null) return;
        for (Action a : actions()) if (a.key().get() == key) a.run().run();
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
}
