package dev.prismglass.module;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.util.KeyUtil;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;

/**
 * Owns every module. Hooks are dispatched only to enabled modules (kept in a separate
 * copy-on-write list, so the netty thread can iterate it safely while the main thread toggles).
 */
public final class ModuleManager {
    private final List<Module> modules = new ArrayList<>();
    private final Map<Class<? extends Module>, Module> byClass = new HashMap<>();
    private final List<Module> enabled = new CopyOnWriteArrayList<>();

    public void register(Module... list) {
        for (Module m : list) {
            modules.add(m);
            byClass.put(m.getClass(), m);
        }
        modules.sort(Comparator.comparing(Module::getName));
    }

    public List<Module> all() { return modules; }

    public List<Module> enabled() { return enabled; }

    public List<Module> byCategory(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) if (m.getCategory() == c) out.add(m);
        return out;
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> type) { return (T) byClass.get(type); }

    /** Names other clients use for modules Prism has under another name (commands and ClickGUI search take both). */
    public static final java.util.Map<String, String> ALIASES = java.util.Map.ofEntries(
        java.util.Map.entry("autocrystal", "CrystalAura"),
        java.util.Map.entry("citybreaker", "AutoCity"),
        java.util.Map.entry("blockesp", "Search"),
        java.util.Map.entry("nobob", "NoRender"),
        java.util.Map.entry("strafe", "Speed"),
        java.util.Map.entry("bedbomb", "BedAura"),
        java.util.Map.entry("wallhack", "Xray"),
        java.util.Map.entry("expthrower", "AutoXP"),
        java.util.Map.entry("mazekill", "TunnelKill"),
        java.util.Map.entry("middleclickextra", "MiddleClick"));

    public Module get(String name) {
        for (Module m : modules) if (m.getName().equalsIgnoreCase(name)) return m;
        String alias = ALIASES.get(name.toLowerCase(java.util.Locale.ROOT));
        if (alias != null) for (Module m : modules) if (m.getName().equalsIgnoreCase(alias)) return m;
        return null;
    }

    /** Whether a ClickGUI search hits this module by name or by one of its aliases. */
    public static boolean matches(Module m, String lowerQuery) {
        if (m.getName().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) return true;
        for (var e : ALIASES.entrySet()) if (e.getValue().equals(m.getName()) && e.getKey().contains(lowerQuery)) return true;
        return false;
    }

    void markEnabled(Module m) { if (!enabled.contains(m)) enabled.add(m); }
    void markDisabled(Module m) { enabled.remove(m); }

    // ---- dispatch ---------------------------------------------------------------------------

    private long lastCoreError;

    private void each(Consumer<Module> action) {
        for (Module m : enabled) {
            try {
                action.accept(m);
            } catch (Throwable t) {
                // the HUD and AntiCheat are core: log and keep them, a one-frame glitch must not switch them off for good
                if (m instanceof dev.prismglass.module.client.Hud || m instanceof dev.prismglass.module.client.AntiCheat) {
                    if (System.currentTimeMillis() - lastCoreError > 10_000) Prism.LOG.error("Module {} threw in a hook", m.getName(), t);
                    lastCoreError = System.currentTimeMillis();
                    continue;
                }
                Prism.LOG.error("Module {} crashed in a hook; disabling it", m.getName(), t);
                m.setEnabledSilently(false);
            }
        }
    }

    public void onTick() { each(Module::onTick); }
    public void onPostTick() { each(Module::onPostTick); }
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) { each(m -> m.onRender2D(ctx, delta)); }
    public void onRender3D(PoseStack matrices, float delta) { each(m -> m.onRender3D(matrices, delta)); }
    public void onMove(MoveEvent e) { each(m -> m.onMove(e)); }
    public void onAttack(Entity target) { each(m -> m.onAttack(target)); }
    public void onWorldJoin() { each(Module::onWorldJoin); }

    public void onPacketSend(PacketEvent e) {
        for (Module m : enabled) {
            try { m.onPacketSend(e); } catch (Throwable t) { Prism.LOG.error("{} onPacketSend", m.getName(), t); }
            if (e.isCancelled()) return;
        }
    }

    public void onPacketReceive(PacketEvent e) {
        for (Module m : enabled) {
            try { m.onPacketReceive(e); } catch (Throwable t) { Prism.LOG.error("{} onPacketReceive", m.getName(), t); }
            if (e.isCancelled()) return;
        }
    }

    /** Keybinds. {@code pressed} false = released (for Hold bind mode). */
    public void onKey(int key, boolean pressed) {
        if (key == KeyUtil.NONE) return;
        if (pressed) {
            var actions = get(dev.prismglass.module.client.KeyActions.class);
            if (actions != null) actions.onKeyPress(key);
        }
        for (Module m : modules) {
            if (m.bind.get() != key) continue;
            if (m.bindMode.is("Hold")) m.setEnabled(pressed);
            else if (pressed) m.toggle();
        }
    }
}
