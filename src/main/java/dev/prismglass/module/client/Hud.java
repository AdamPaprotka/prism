package dev.prismglass.module.client;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.gui.render.PrismLogo;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.TextSetting;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.DamageUtil;
import dev.prismglass.util.InvUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Glass HUD: watermark, module list, info, armour, counters, target card and toggle toasts.
 *
 * <p>Every element can be dragged in the HUD editor (ClickGUI "HUD" button or {@code .hud}). A moved element's
 * position is stored as a fraction of the free screen space, (x / (screenW - w), y / (screenH - h)), so it keeps
 * hugging the same edge at any resolution or GUI scale.
 */
public class Hud extends Module {
    public final BoolSetting watermark = bool("Watermark", true, "Logo + client name.");
    public final BoolSetting arrayList = bool("ArrayList", true, "Enabled modules list.");
    public final ModeSetting arraySide = mode("ArraySide", "Right", "Which side the list sits on (until you drag it in the HUD editor).", "Right", "Left");
    public final ModeSetting listFormat = mode("ListFormat", "Classic", "Classic = Name info. Brackets = [AC mode] [Name] [info].", "Classic", "Brackets");
    public final BoolSetting info = bool("Info", true, "Coordinates, FPS, ping, TPS, speed.");
    public final BoolSetting netherCoords = bool("NetherCoords", true, "Show the other dimension's coordinates.");
    public final BoolSetting armor = bool("Armor", true, "Armour with durability above the hotbar.");
    public final BoolSetting counters = bool("Counters", true, "Totem / crystal / gapple / xp counters.");
    public final BoolSetting targetHud = bool("TargetHud", true, "Card for the current combat target.");
    public final BoolSetting toasts = bool("Toasts", true, "Glass toast when modules toggle.");
    public final BoolSetting rainbowList = bool("RainbowList", true, "Rainbow accent bars in the list.");
    /** Dragged positions, "name:fx,fy;..." (edited with the HUD editor, hidden in the GUI). */
    public final TextSetting layout = text("Layout", "", "HUD element positions (drag them in the HUD editor).").visibleWhen(() -> false);

    /** Set by combat modules each tick; cleared when they lose the target. */
    public static LivingEntity combatTarget;
    private static long combatTargetTime;

    /** Module toggle (message == null) or a Prism message shown here instead of chat while StreamProof is on. */
    private record Toast(String text, boolean on, long time, String message, int color, long life) {}
    private static final Deque<Toast> TOASTS = new ArrayDeque<>();

    /** HUD editor open: draw placeholders for empty elements so they can be moved. */
    public static boolean editing;

    /** Where each element was drawn last frame (x, y, w, h), in draw order; the editor reads this. */
    private final Map<String, float[]> bounds = new LinkedHashMap<>();
    private final Map<String, float[]> positions = new LinkedHashMap<>();
    private String parsedLayout;

    private long lastTimePacket;
    private float tps = 20f;

    public Hud() {
        super("HUD", "Heads-up display.", Category.CLIENT);
        drawn.set(false);
        setEnabledSilently(true);
    }

    public static void setTarget(LivingEntity e) {
        combatTarget = e;
        combatTargetTime = System.currentTimeMillis();
    }

    public static void onToggle(Module m) {
        Hud hud = Prism.modules() == null ? null : Prism.modules().get(Hud.class);
        if (hud == null || !hud.isEnabled() || !hud.toasts.get() || m == hud) return;
        synchronized (TOASTS) {
            TOASTS.addFirst(new Toast(m.getName(), m.isEnabled(), System.currentTimeMillis(), null, 0, 2200));
            while (TOASTS.size() > 8) TOASTS.removeLast();
        }
    }

    /** Shows a Prism message as a toast; false if the HUD is off (caller decides what to do then). */
    public static boolean message(String text, int color) {
        Hud hud = Prism.modules() == null ? null : Prism.modules().get(Hud.class);
        if (hud == null || !hud.isEnabled()) return false;
        synchronized (TOASTS) {
            TOASTS.addFirst(new Toast(text, true, System.currentTimeMillis(), text, color, 4500));
            while (TOASTS.size() > 8) TOASTS.removeLast();
        }
        return true;
    }

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (event.packet instanceof ClientboundSetTimePacket) {
            long now = System.currentTimeMillis();
            if (lastTimePacket != 0) {
                float seconds = (now - lastTimePacket) / 1000f;
                tps = Mth.clamp(20f / Math.max(seconds, 0.05f), 0f, 20f) * 0.3f + tps * 0.7f;
            }
            lastTimePacket = now;
        }
    }

    // ---- layout (HUD editor) ---------------------------------------------------------------------------

    /** Last frame's element rectangles, for the editor. */
    public Map<String, float[]> bounds() { return bounds; }

    /** Store a dragged element's top-left (screen GUI units). */
    public void setPosition(String name, float x, float y, float w, float h, int sw, int sh) {
        parse();
        float fx = sw - w <= 0 ? 0 : Mth.clamp(x / (sw - w), 0, 1), fy = sh - h <= 0 ? 0 : Mth.clamp(y / (sh - h), 0, 1);
        positions.put(name, new float[]{fx, fy});
        save();
    }

    public void resetPosition(String name) {
        parse();
        positions.remove(name);
        save();
    }

    public boolean isMoved(String name) { parse(); return positions.containsKey(name); }

    private void parse() {
        String s = layout.get();
        if (s.equals(parsedLayout)) return;
        positions.clear();
        for (String part : s.split(";")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            String[] xy = kv[1].split(",");
            try {
                positions.put(kv[0], new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])});
            } catch (RuntimeException ignored) {}
        }
        parsedLayout = s;
    }

    private void save() {
        StringBuilder b = new StringBuilder();
        for (var e : positions.entrySet()) {
            if (b.length() > 0) b.append(';');
            b.append(e.getKey()).append(':').append(String.format(java.util.Locale.ROOT, "%.4f,%.4f", e.getValue()[0], e.getValue()[1]));
        }
        layout.set(b.toString());
        parsedLayout = layout.get();
    }

    /** Where to draw an element of this size: the dragged spot, else its default. Records the bounds. */
    private float[] place(String name, float w, float h, float defX, float defY, int sw, int sh) {
        parse();
        float[] f = positions.get(name);
        float x = f == null ? defX : f[0] * Math.max(0, sw - w), y = f == null ? defY : f[1] * Math.max(0, sh - h);
        bounds.put(name, new float[]{x, y, w, h});
        return new float[]{x, y};
    }

    // ---- drawing -----------------------------------------------------------------------------------

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (mc.options.hideGui && !editing) return;
        bounds.clear();
        ClickGui theme = Prism.modules().get(ClickGui.class);
        Glass.Style style = theme.panelStyle();
        if (watermark.get()) drawWatermark(ctx, style, theme);
        if (arrayList.get()) drawArrayList(ctx, theme);
        if (info.get()) drawInfo(ctx, style);
        if (armor.get()) drawArmor(ctx);
        if (counters.get()) drawCounters(ctx, style);
        if (targetHud.get()) drawTarget(ctx, style, theme);
        if (toasts.get()) drawToasts(ctx, style, theme);
    }

    private void drawWatermark(GuiGraphicsExtractor ctx, Glass.Style style, ClickGui theme) {
        String text = Prism.NAME + " §7" + Prism.VERSION;
        float w = mc.font.width(text) + 30, h = 18;
        float[] p = place("Watermark", w, h, 4, 4, ctx.guiWidth(), ctx.guiHeight());
        Glass.panel(ctx, p[0], p[1], w, h, style);
        PrismLogo.draw(ctx, p[0] + 10, p[1] + 9.5f, 11, theme.logoSpeed.getFloat(), 1f);
        ctx.text(mc.font, text, (int) p[0] + 21, (int) p[1] + 5, 0xFFFFFFFF, true);
    }

    private void drawArrayList(GuiGraphicsExtractor ctx, ClickGui theme) {
        List<Module> list = new ArrayList<>();
        for (Module m : Prism.modules().all()) {
            float target = m.isEnabled() && m.drawn.get() ? 1f : 0f;
            m.arrayAnim += (target - m.arrayAnim) * 0.18f;
            if (Math.abs(m.arrayAnim - target) < 0.01f) m.arrayAnim = target;
            if (m.arrayAnim > 0.01f) list.add(m);
        }
        list.sort(Comparator.comparingInt((Module m) -> -mc.font.width(label(m))));
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        float boxW = 0, boxH = 0;
        for (Module m : list) {
            boxW = Math.max(boxW, mc.font.width(label(m)) + 10);
            boxH += 11 * m.arrayAnim + 1;
        }
        if (list.isEmpty() && editing) { boxW = mc.font.width("Module list") + 10; boxH = 12; }
        boolean defRight = arraySide.is("Right");
        float[] p = place("ArrayList", boxW, boxH, defRight ? sw - 4 - boxW : 4, defRight ? 4 : 26, sw, sh);
        // align to the nearer side and grow away from the nearer edge
        boolean right = isMoved("ArrayList") ? p[0] + boxW / 2 > sw / 2f : defRight;
        boolean up = isMoved("ArrayList") && p[1] + boxH / 2 > sh / 2f;
        if (list.isEmpty()) {
            if (editing) ctx.text(mc.font, "§7Module list", (int) p[0] + 5, (int) p[1] + 2, 0xFFFFFFFF, true);
            return;
        }
        float y = up ? p[1] + boxH : p[1];
        int i = 0;
        for (Module m : list) {
            String text = label(m);
            float a = m.arrayAnim;
            float tw = mc.font.width(text);
            float w = tw + 10, h = 11;
            float x = right ? p[0] + boxW - w * a : p[0] - w * (1 - a);
            if (up) y -= h * a + 1;
            int acc = rainbowList.get() ? ColorUtil.rainbow(i * 180, 255) : theme.accent.color();
            Glass.rounded(ctx, x, y, w, h * a + 0.5f, 3, ColorUtil.fade(0x70202838, a), ColorUtil.fade(0x50101820, a));
            float barX = right ? x + w - 1.5f : x;
            ctx.fill((int) barX, (int) y + 1, (int) barX + 2, (int) (y + h * a - 1), ColorUtil.fade(acc, a));
            ctx.text(mc.font, text, (int) (x + 5), (int) (y + 2), ColorUtil.fade(ColorUtil.lerp(acc, 0xFFFFFFFF, 0.55f), a), true);
            if (!up) y += h * a + 1;
            i++;
        }
    }

    private String label(Module m) {
        String info = m.getInfo();
        if (listFormat.is("Brackets")) {
            // §r goes back to the line's accent colour for the name
            String s = "§8[§7" + Prism.anticheat().preset.get() + "§8] [§r" + m.getName() + "§8]";
            return info == null ? s : s + " §8[§7" + info + "§8]";
        }
        return info == null ? m.getName() : m.getName() + " §7" + info;
    }

    private void drawInfo(GuiGraphicsExtractor ctx, Glass.Style style) {
        List<String> lines = new ArrayList<>();
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        String coords = String.format("XYZ §f%.1f %.1f %.1f", x, y, z);
        if (netherCoords.get()) {
            boolean nether = mc.level.dimension() == Level.NETHER;
            double f = nether ? 8 : 0.125;
            coords += String.format(" §7[§f%.1f %.1f§7]", x * f, z * f);
        }
        lines.add(coords);
        PlayerInfo entry = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        int ping = entry == null ? 0 : entry.getLatency();
        double bps = Math.hypot(x - mc.player.xo, z - mc.player.zo) * 20;
        lines.add(String.format("FPS §f%d §7Ping §f%d §7TPS §f%.1f §7Speed §f%.1f", mc.getFps(), ping, tps, bps));

        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        float w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        w += 12;
        float h = lines.size() * 10 + 6;
        float[] p = place("Info", w, h, 4, sh - h - 4, sw, sh);
        float py = p[1];
        // stay above the chat box when it's open and we sit on the bottom edge
        if (mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen && py + h > sh - 18) py -= 14;
        Glass.panel(ctx, p[0], py, w, h, style);
        for (int i = 0; i < lines.size(); i++) ctx.text(mc.font, "§7" + lines.get(i), (int) p[0] + 6, (int) py + 4 + i * 10, 0xFFFFFFFF, true);
    }

    private void drawArmor(GuiGraphicsExtractor ctx) {
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        float w = 72, h = 22; // four items + the durability labels above them
        float[] p = place("Armor", w, h, sw / 2f + 12, sh - 62 - (mc.player.isUnderWater() ? 10 : 0), sw, sh);
        int x = (int) p[0], y = (int) p[1] + 6;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        boolean any = false;
        for (EquipmentSlot slot : slots) {
            ItemStack s = mc.player.getItemBySlot(slot);
            if (!s.isEmpty()) {
                any = true;
                ctx.item(s, x, y);
                if (s.isDamageableItem()) {
                    int pct = Math.round(100f * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage());
                    int col = ColorUtil.lerp(0xFFFF5555, 0xFF55FF88, pct / 100f);
                    String t = pct + "%";
                    ctx.pose().pushMatrix();
                    ctx.pose().translate((float) (x + 8), (float) (y - 5));
                    ctx.pose().scale(0.6f, 0.6f);
                    ctx.text(mc.font, t, -mc.font.width(t) / 2, 0, col, true);
                    ctx.pose().popMatrix();
                }
            }
            x += 18;
        }
        if (!any && editing) ctx.text(mc.font, "§7Armor", (int) p[0] + 4, (int) p[1] + 8, 0xFFFFFFFF, true);
    }

    private void drawCounters(GuiGraphicsExtractor ctx, Glass.Style style) {
        Item[] items = {Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL, Items.ENCHANTED_GOLDEN_APPLE, Items.EXPERIENCE_BOTTLE, Items.OBSIDIAN};
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        List<Item> shown = new ArrayList<>();
        for (Item it : items) if (InvUtil.count(it) > 0) shown.add(it);
        if (shown.isEmpty() && editing) shown.add(Items.TOTEM_OF_UNDYING);
        if (shown.isEmpty()) return;
        float w = shown.size() * 20 + 4, h = 22;
        float[] p = place("Counters", w, h, sw - w - 4, sh - 26, sw, sh);
        Glass.panel(ctx, p[0], p[1], w, h, style);
        for (int i = 0; i < shown.size(); i++) {
            Item it = shown.get(i);
            int ix = (int) p[0] + 3 + i * 20, iy = (int) p[1] + 3;
            int count = InvUtil.count(it);
            ctx.item(new ItemStack(it), ix, iy);
            ctx.itemDecorations(mc.font, new ItemStack(it, Math.max(1, Math.min(99, count))), ix, iy, String.valueOf(count));
        }
    }

    private void drawTarget(GuiGraphicsExtractor ctx, Glass.Style style, ClickGui theme) {
        LivingEntity t = combatTarget;
        if (t == null || !t.isAlive() || System.currentTimeMillis() - combatTargetTime > 1500) {
            if (!editing) return;
            t = mc.player; // preview
        }
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        float w = 130, h = 40;
        float[] p = place("TargetHud", w, h, sw / 2f + 12, sh / 2f + 12, sw, sh);
        float x = p[0], y = p[1];
        Glass.panel(ctx, x, y, w, h, style);
        String name = t.getName().getString();
        ctx.text(mc.font, name, (int) x + 8, (int) y + 6, 0xFFFFFFFF, true);
        float hp = DamageUtil.health(t), max = t.getMaxHealth() + t.getAbsorptionAmount();
        String hpText = String.format("%.1f", hp);
        ctx.text(mc.font, hpText, (int) (x + w - 8 - mc.font.width(hpText)), (int) y + 6, 0xFFFFFFFF, true);
        float pct = Mth.clamp(hp / Math.max(1, max), 0, 1);
        Glass.rounded(ctx, x + 8, y + 19, w - 16, 5, 2.5f, 0x40FFFFFF, 0x30FFFFFF);
        int col = ColorUtil.lerp(0xFFFF4D6A, 0xFF5CFF9D, pct);
        Glass.rounded(ctx, x + 8, y + 19, Math.max(5, (w - 16) * pct), 5, 2.5f, col, ColorUtil.lerp(col, 0xFF000000, 0.25f));
        if (t instanceof Player pl) {
            String sub = String.format("%.1fm", mc.player.distanceTo(pl));
            int totems = pl.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? 1 : 0;
            if (totems > 0) sub += "  §6totem";
            ctx.text(mc.font, "§7" + sub, (int) x + 8, (int) y + 28, 0xFFFFFFFF, false);
        }
    }

    private void drawToasts(GuiGraphicsExtractor ctx, Glass.Style style, ClickGui theme) {
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        // the element is the newest toast's slot; older ones stack away from the nearer screen edge
        float slotW = 140, slotH = 16;
        float[] p = place("Toasts", slotW, slotH, sw - 4 - slotW, sh - 54, sw, sh);
        boolean moved = isMoved("Toasts");
        boolean right = !moved || p[0] + slotW / 2 > sw / 2f;
        boolean up = !moved || p[1] + slotH / 2 > sh / 2f;
        long now = System.currentTimeMillis();
        float y = p[1];
        synchronized (TOASTS) {
            TOASTS.removeIf(t -> now - t.time() > t.life());
            if (TOASTS.isEmpty() && editing) {
                Glass.panel(ctx, p[0], p[1], slotW, slotH, style);
                ctx.text(mc.font, "§7Toasts", (int) p[0] + 8, (int) p[1] + 4, 0xFFFFFFFF, false);
                return;
            }
            for (Toast t : TOASTS) {
                float age = (now - t.time()) / (float) t.life();
                float a = age < 0.1f ? age / 0.1f : age > 0.8f ? (1 - age) / 0.2f : 1f;
                String text = t.message() != null ? t.message() : t.text() + (t.on() ? " §aon" : " §coff");
                float w = mc.font.width(text) + 16;
                float slide = w * Math.min(1f, a * 1.4f);
                float x = right ? p[0] + slotW - slide : p[0] - w + slide;
                Glass.panel(ctx, x, y, w, 16, style.withAlpha(a));
                int col = t.message() != null ? t.color() : 0xFFFFFFFF;
                ctx.text(mc.font, text, (int) x + 8, (int) y + 4, ColorUtil.fade(col, Math.max(0.05f, a)), false);
                y += up ? -19 : 19;
            }
        }
    }
}
