package dev.prismglass.module;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.KeyUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Base class for every feature. Subclasses declare settings as fields using the helper
 * factories (which register them in order) and override only the hooks they need.
 * Hooks are only invoked while the module is enabled and a world is loaded.
 */
public abstract class Module {
    protected static final Minecraft mc = Minecraft.getInstance();

    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();

    private boolean enabled;
    public final KeySetting bind = new KeySetting("Bind", "Key that toggles this module.", KeyUtil.NONE);
    public final BoolSetting drawn = new BoolSetting("Drawn", "Show in the HUD module list.", true);
    public final ModeSetting bindMode = new ModeSetting("BindMode", "Toggle on press, or only while held.", "Toggle", "Toggle", "Hold");
    public final BoolSetting notify = new BoolSetting("Notify", "Chat message when toggled.", false);

    /** Animation progress used by the HUD array list (0 hidden .. 1 shown). */
    public float arrayAnim;

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    // ---- setting factories --------------------------------------------------------------

    protected <S extends Setting<?>> S add(S s) { settings.add(s); return s; }

    protected BoolSetting bool(String name, boolean def, String desc) { return add(new BoolSetting(name, desc, def)); }

    protected NumberSetting num(String name, double def, double min, double max, double step, String desc) {
        return add(new NumberSetting(name, desc, def, min, max, step));
    }

    protected ModeSetting mode(String name, String def, String desc, String... modes) {
        return add(new ModeSetting(name, desc, def, modes));
    }

    protected ColorSetting color(String name, int argb, String desc) { return add(new ColorSetting(name, desc, argb)); }

    protected KeySetting key(String name, int def, String desc) { return add(new KeySetting(name, desc, def)); }

    protected TextSetting text(String name, String def, String desc) { return add(new TextSetting(name, desc, def)); }

    /** List of block or item ids, edited in the GUI's list editor. */
    protected ListSetting list(String name, String def, String desc, ListSetting.Kind kind) {
        return add(new ListSetting(name, desc, def, kind, false, 0, List.of()));
    }

    /** List picked from fixed options; ordered lists allow duplicates and reordering. */
    protected ListSetting choices(String name, String def, String desc, boolean ordered, int maxSize, String... options) {
        return add(new ListSetting(name, desc, def, ListSetting.Kind.CHOICE, ordered, maxSize, List.of(options)));
    }

    /** User settings followed by the shared bind/drawn/notify settings. */
    public List<Setting<?>> getSettings() {
        List<Setting<?>> all = new ArrayList<>(settings);
        all.add(bind);
        all.add(bindMode);
        all.add(drawn);
        all.add(notify);
        return all;
    }

    public Setting<?> getSetting(String name) {
        for (Setting<?> s : getSettings()) if (s.getName().equalsIgnoreCase(name)) return s;
        return null;
    }

    // ---- state --------------------------------------------------------------------------

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) {
            Prism.modules().markEnabled(this);
            if (!nullCheck()) safe(this::onEnable);
        } else {
            Prism.modules().markDisabled(this);
            if (!nullCheck()) safe(this::onDisable);
        }
        // a module that switched itself straight back (e.g. ClickGui just opening a screen) isn't a toggle
        if (enabled != value) return;
        if (mc.player != null) dev.prismglass.module.client.Hud.onToggle(this);
        if (notify.get() && mc.player != null) {
            ChatUtil.info(name + (value ? " §aenabled" : " §cdisabled"));
        }
    }

    /** Enables without firing onEnable (used by config load before a world exists). */
    public void setEnabledSilently(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) Prism.modules().markEnabled(this); else Prism.modules().markDisabled(this);
    }

    private void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { Prism.LOG.error("Module {} threw", name, t); }
    }

    public boolean isEnabled() { return enabled; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }

    /** Extra text shown greyed out next to the name in the array list. */
    public String getInfo() { return null; }

    protected static boolean nullCheck() { return mc.player == null || mc.level == null; }

    // ---- hooks --------------------------------------------------------------------------

    public void onEnable() {}
    public void onDisable() {}
    /** Before the local player ticks (before input, movement and packets). */
    public void onTick() {}
    /** After the local player ticked and movement packets were sent. */
    public void onPostTick() {}
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {}
    public void onRender3D(PoseStack matrices, float delta) {}
    public void onPacketSend(PacketEvent event) {}
    public void onPacketReceive(PacketEvent event) {}
    public void onMove(MoveEvent event) {}
    /** Right before the client attacks an entity (any source: vanilla click or a module). */
    public void onAttack(net.minecraft.world.entity.Entity target) {}
    /** Called once when joining a world (and on game start). */
    public void onWorldJoin() {}
}
