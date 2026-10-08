package dev.prismglass.gui;

import com.google.gson.JsonObject;
import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.gui.render.PrismLogo;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.KeyUtil;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Meteor-style ClickGUI on liquid glass, immediate-mode: every frame lays out and draws all panels and
 * records clickable regions; input events are resolved against the regions of the last frame.
 *
 * <p>Controls: left click module = toggle, right click = settings, middle click = bind.
 * Drag panel headers to move, right click a header to collapse. Type anywhere to search.
 * Number sliders drag; modes cycle with left/right click; colours expand into HSB/A sliders.
 */
public class ClickGuiScreen extends Screen {
    private static final int HEADER_H = 20, MODULE_H = 16, SETTING_H = 14, SLIDER_H = 18, PAD = 4;
    private static final float GAP = 6;
    private static final int TEXT = 0xFFF4F7FB, DIM = 0xFFAEB8C8, FAINT = 0x66FFFFFF;

    // ---- persistent layout -----------------------------------------------------------------
    private static final class PanelState {
        float x, y, scroll;
        boolean open = true;
        float openAnim = 1f;
    }

    private static final Map<Category, PanelState> PANELS = new EnumMap<>(Category.class);
    private static final Set<Module> EXPANDED = new HashSet<>();
    private static final Set<ColorSetting> EXPANDED_COLORS = new HashSet<>();

    static {
        for (Category c : Category.values()) PANELS.put(c, new PanelState());
        resetLayout();
    }

    /** Panels side by side under the top bar, all open. */
    public static void resetLayout() {
        int i = 0;
        for (Category c : Category.values()) {
            PanelState p = PANELS.get(c);
            p.x = 10 + i * 124;
            p.y = 46;
            p.scroll = 0;
            p.open = true;
            p.openAnim = 1f;
            i++;
        }
    }

    // ---- per-frame state ---------------------------------------------------------------------
    private interface Click { void click(int button, double mx, double my); }
    private interface Drag { void drag(double mx, double my); }
    private record Hit(float x, float y, float w, float h, Click click, Drag drag, String tooltip) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final Map<Module, Float> expandAnim = new HashMap<>();
    private final Map<Module, Float> enableAnim = new HashMap<>();
    private final Map<BoolSetting, Float> toggleAnim = new HashMap<>();
    private long lastToggleFrame = System.nanoTime();
    private float toggleDt;
    private Category dragging;
    private float dragOffX, dragOffY;
    private Drag activeDrag;
    private KeySetting listening;
    private TextSetting editing;
    private String search = "";
    private String tooltip;
    private long openedAt;
    private final ClickGui theme;

    // Config | Profiles bar next to the search
    private enum Menu { NONE, CONFIG, PROFILES, KEYBINDS }
    private Menu menu = Menu.NONE;
    private float menuAnim, menuScroll;
    private float menuX, menuY, menuW, menuH;   // last frame's dropdown rect
    private float barX, barY, barW, barH;
    private List<String> profileCache = List.of();
    private String newProfile;                  // non-null while typing a new profile name
    private String deleteArmed, armedPrev;      // right click twice to delete
    private String status;
    private long statusAt;
    private int lastMouseX, lastMouseY;
    private boolean cursorHidden; // StreamProof: OS cursor hidden, ours drawn in the hidden layer
    // search only takes keys after you click it, so binds work in the GUI; clears on Esc or after 5 s idle
    private static final long SEARCH_IDLE_MS = 5000;
    private boolean searchFocused;
    private long lastSearchInput;

    public ClickGuiScreen() {
        super(Component.literal("Prism"));
        theme = Prism.modules().get(ClickGui.class);
    }

    @Override protected void init() { openedAt = System.currentTimeMillis(); }
    @Override public boolean isPauseScreen() { return false; }

    // ---- rendering ---------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // no world (main menu): vanilla panorama background
        if (minecraft.level == null) { super.extractBackground(ctx, mouseX, mouseY, delta); return; }
        if (theme.blur.get()) extractBlurredBackground(ctx);
        dev.prismglass.streamproof.Overlay.markGui(); // StreamProof: the dim and everything after is hidden from capture
        // liquid glass wants the world visible behind it, so only a light dim
        if (theme.style.is("Liquid")) ctx.fillGradient(0, 0, width, height, 0x18101524, 0x40080A12);
        else ctx.fillGradient(0, 0, width, height, 0x40101524, 0x70080A12);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        Glass.pixel = theme.blocky.get() ? theme.blockSize.getFloat() : 0;
        try {
            extractGui(ctx, mouseX, mouseY, delta);
        } finally {
            Glass.pixel = 0; // HUD and other screens stay smooth
        }
    }

    private void extractGui(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // 26.1: the background was already extracted by Screen before this call
        dev.prismglass.streamproof.Overlay.markGui();
        long now = System.nanoTime();
        toggleDt = Math.min(0.1f, (now - lastToggleFrame) / 1e9f);
        lastToggleFrame = now;
        Glass.captureBackground(ctx);
        hits.clear();
        tooltip = null;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if ((searchFocused || !search.isEmpty()) && System.currentTimeMillis() - lastSearchInput > SEARCH_IDLE_MS) {
            search = "";
            searchFocused = false;
        }
        float intro = easeOut(Math.min(1f, (System.currentTimeMillis() - openedAt) / (220f / theme.animSpeed.getFloat())));

        ctx.pose().pushMatrix();
        ctx.pose().translate(0f, (1 - intro) * 12f);
        drawTopBar(ctx, intro);
        for (Category c : Category.values()) drawPanel(ctx, c, mouseX, mouseY, delta, intro);
        drawMenu(ctx, mouseX, mouseY, delta);
        drawListEditor(ctx, delta);
        ctx.pose().popMatrix();

        if (moduleFlash != null && System.currentTimeMillis() - moduleFlashAt < 2000) tooltip = moduleFlash;
        if (tooltip != null && theme.descriptions.get()) drawTooltip(ctx, tooltip);
        updateCursor();
        if (cursorHidden) drawSoftCursor(ctx);
        Glass.invalidate();
    }

    private void drawTopBar(GuiGraphicsExtractor ctx, float alpha) {
        Font tr = font;
        String label = searchFocused ? search + (blink() ? "_" : "") : search.isEmpty() ? "Click to search..." : search;
        float w = Math.max(150, tr.width(label) + 46);
        float x = width / 2f - GAP / 2f - w, y = 10; // search ends and config starts around the centre line
        Glass.panel(ctx, x, y, w, 24, styleWith(alpha));
        hits.add(new Hit(x, y, w, 24, (b, cx, cy) -> { searchFocused = true; lastSearchInput = System.currentTimeMillis(); }, null, null));
        if (searchFocused) fill(ctx, (int) (x + 30), (int) (y + 18), (int) (x + w - 12), (int) (y + 19), ColorUtil.withAlpha(theme.accent.color(), 170));
        if (theme.logo.get()) PrismLogo.draw(ctx, x + 15, y + 12.5f, 15, theme.logoSpeed.getFloat(), alpha);
        // the logo (added after the search hit, so it wins): About - version, channel and build details
        hits.add(new Hit(x + 2, y + 1, 26, 22, (b, cx, cy) -> minecraft.setScreen(new AboutScreen(this)), null, "About Prism (version, build, debug info)"));
        ctx.text(tr, label, (int) (x + 30), (int) (y + 8), search.isEmpty() ? DIM : TEXT, false);
        drawConfigBar(ctx, width / 2f + GAP / 2f, y, alpha);
    }

    // ---- Config | Profiles ------------------------------------------------------------------------

    private void drawConfigBar(GuiGraphicsExtractor ctx, float x, float y, float alpha) {
        String[] labels = {"Config", "Profiles", "Keybinds", "HUD"};
        Menu[] targets = {Menu.CONFIG, Menu.PROFILES, Menu.KEYBINDS, null}; // HUD opens the editor instead of a menu
        String[] tips = {"Save, reload, layout and the ClickGUI look.", "Switch, create and delete profiles.",
            "Keys for things that aren't modules: Inspect, Quick Pearl, waypoints...", "Drag HUD elements around with the mouse."};
        float[] widths = new float[labels.length];
        float total = 0;
        for (int i = 0; i < labels.length; i++) total += widths[i] = font.width(labels[i]) + 20;
        barX = x; barY = y; barW = total; barH = 24;
        Glass.panel(ctx, x, y, barW, barH, styleWith(alpha));
        float sx = x;
        for (int i = 0; i < labels.length; i++) {
            float sw = widths[i];
            Menu target = targets[i];
            boolean active = target != null && menu == target;
            boolean hover = mouseIn(sx, y, sw, barH);
            float r = Math.max(2, theme.radius.getFloat() - 3);
            if (active) {
                int acc = theme.accent.color();
                Glass.rounded(ctx, sx + 3, y + 3, sw - 6, barH - 6, r, ColorUtil.withAlpha(acc, 150), ColorUtil.withAlpha(acc, 80));
            } else if (hover) {
                Glass.rounded(ctx, sx + 3, y + 3, sw - 6, barH - 6, r, 0x22FFFFFF, 0x12FFFFFF);
            }
            if (hover) tooltip = tips[i];
            ctx.text(font, labels[i], (int) (sx + 10), (int) (y + 8), active ? TEXT : DIM, active);
            Click action = target != null ? (b, cx, cy) -> openMenu(menu == target ? Menu.NONE : target) : (b, cx, cy) -> openHudEditor();
            hits.add(new Hit(sx, y, sw, barH, action, null, tips[i]));
            if (i > 0) fill(ctx, (int) sx, (int) (y + 6), (int) sx + 1, (int) (y + barH - 6), FAINT);
            sx += sw;
        }
    }

    private void openHudEditor() {
        if (minecraft.level == null) {
            moduleFlash = "Join a world to edit the HUD";
            moduleFlashAt = System.currentTimeMillis();
            return;
        }
        minecraft.setScreen(new HudEditorScreen(this));
    }

    // ---- list editor (ListSetting) --------------------------------------------------------------------

    private ListSetting listEditing;
    private Module listOwner;
    private String listInput = "";
    private float listScroll, listAnim;
    private int popupHitStart = -1;
    private String sugKey;
    private List<String> sugCache = List.of();
    private long listInvalidAt;

    private void openList(Module owner, ListSetting l) {
        openMenu(Menu.NONE);
        listEditing = l;
        listOwner = owner;
        listInput = "";
        listScroll = 0;
        listAnim = 0;
        sugKey = null;
    }

    private void closeList() {
        listEditing = null;
        popupHitStart = -1;
    }

    private void drawListEditor(GuiGraphicsExtractor ctx, float delta) {
        if (listEditing == null) { popupHitStart = -1; return; }
        ListSetting l = listEditing;
        listAnim = approach(listAnim, 1f, delta);
        float a = easeOut(listAnim);
        ctx.nextStratum();
        fill(ctx, 0, 0, width, height, ColorUtil.withAlpha(0xFF05070C, (int) (110 * a)));
        tooltip = null;
        popupHitStart = hits.size();
        hits.add(new Hit(0, 0, width, height, (b, cx, cy) -> closeList(), null, null)); // click outside closes

        List<String> items = l.items();
        List<String> sugg = suggestions(l);
        float w = 230, rowH = 18;
        float fixed = PAD + 22 + 18 + 4 + sugg.size() * 18 + 8 + 24 + PAD;
        float listMax = Math.max(rowH * 2, height - 30 - fixed);
        float listH = Math.min(Math.max(items.size(), 1) * rowH, listMax);
        float h = fixed + listH;
        float x = width / 2f - w / 2f, y = height / 2f - h / 2f + (1 - a) * 10;
        Glass.panel(ctx, x, y, w, h, menuStyle());
        hits.add(new Hit(x, y, w, h, (b, cx, cy) -> {}, null, null)); // clicks inside don't close

        // header
        float cy = y + PAD;
        ctx.text(font, listOwner.getName() + " \u00b7 " + l.getName(), (int) x + 8, (int) cy + 5, TEXT, true);
        String count = l.maxSize() > 0 ? items.size() + "/" + l.maxSize() : String.valueOf(items.size());
        ctx.text(font, count, (int) (x + w - 26 - font.width(count)), (int) cy + 5, DIM, false);
        button(ctx, "\u00d7", x + w - 22, cy + 1, 16, 16, 0, height, this::closeList);
        cy += 22;

        // add field (always focused while the editor is open)
        boolean bad = System.currentTimeMillis() - listInvalidAt < 600;
        Glass.rounded(ctx, x + 6, cy, w - 12, 18, 4, bad ? 0x60FF4A4A : 0x30FFFFFF, bad ? 0x40FF4A4A : 0x1CFFFFFF);
        String placeholder = switch (l.kind()) {
            case BLOCK -> "Type a block, Enter to add";
            case ITEM -> "Type an item, Enter to add";
            default -> "Type to filter, Enter to add";
        };
        if (l.maxSize() > 0 && items.size() >= l.maxSize()) placeholder = "Full - remove one to add";
        String v = listInput.isEmpty() ? placeholder : listInput + (blink() ? "_" : "");
        ctx.text(font, v, (int) x + 12, (int) cy + 5, listInput.isEmpty() ? FAINT : TEXT, false);
        cy += 22;

        // suggestions
        for (String sug : sugg) {
            if (mouseIn(x + 6, cy, w - 12, 18)) fill(ctx, (int) x + 6, (int) cy, (int) (x + w - 6), (int) cy + 18, 0x22FFFFFF);
            var icon = l.icon(sug);
            if (!icon.isEmpty()) ctx.item(icon, (int) x + 9, (int) cy + 1);
            ctx.text(font, sug, (int) x + 30, (int) cy + 5, DIM, false);
            ctx.text(font, "+", (int) (x + w - 16), (int) cy + 5, theme.accent.color(), false);
            hits.add(new Hit(x + 6, cy, w - 12, 18, (b, cx2, cy2) -> addListEntry(sug), null, null));
            cy += 18;
        }
        cy += 2;
        fill(ctx, (int) x + 6, (int) cy, (int) (x + w - 6), (int) cy + 1, 0x22FFFFFF);
        cy += 6;

        // entries
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, items.size() * rowH - listH));
        int clipTop = (int) cy, clipBottom = (int) (cy + listH);
        ctx.enableScissor((int) x, clipTop, (int) (x + w), clipBottom);
        if (items.isEmpty()) ctx.text(font, "Empty - add something above", (int) x + 10, (int) cy + 5, FAINT, false);
        float ry = cy - listScroll;
        for (int i = 0; i < items.size(); i++, ry += rowH) {
            if (ry + rowH < clipTop || ry > clipBottom) continue;
            String entry = items.get(i);
            int idx = i;
            float top = Math.max(ry, clipTop), bottom = Math.min(ry + rowH, clipBottom);
            if (bottom > top && mouseIn(x + 6, top, w - 12, bottom - top))
                fill(ctx, (int) x + 6, (int) top, (int) (x + w - 6), (int) bottom, 0x14FFFFFF);
            float tx = x + 10;
            if (l.ordered()) {
                ctx.text(font, (i + 1) + "", (int) tx, (int) ry + 5, FAINT, false);
                tx += 12;
            }
            var icon = l.icon(entry);
            if (!icon.isEmpty()) ctx.item(icon, (int) tx, (int) ry + 1);
            ctx.text(font, entry, (int) tx + 20, (int) ry + 5, l.valid(entry) ? TEXT : 0xFFFF8A8A, false);
            float bx = x + w - 22;
            button(ctx, "\u00d7", bx, ry + 2, 14, 14, clipTop, clipBottom, () -> l.remove(idx));
            if (l.ordered()) {
                button(ctx, "\u25bc", bx - 16, ry + 2, 14, 14, clipTop, clipBottom, () -> l.move(idx, 1));
                button(ctx, "\u25b2", bx - 32, ry + 2, 14, 14, clipTop, clipBottom, () -> l.move(idx, -1));
            }
        }
        ctx.disableScissor();
        cy += listH + 6;

        // footer
        float bw = (w - 12 - 8) / 3f;
        button(ctx, "Reset", x + 6, cy, bw, 16, 0, height, () -> { l.reset(); sugKey = null; });
        button(ctx, "Clear", x + 6 + bw + 4, cy, bw, 16, 0, height, () -> { l.setItems(List.of()); sugKey = null; });
        button(ctx, "Done", x + 6 + 2 * (bw + 4), cy, bw, 16, 0, height, this::closeList);
    }

    /** Up to 6 ids matching the typed text: prefix matches first, then anywhere. */
    private List<String> suggestions(ListSetting l) {
        String q = ListSetting.normalize(listInput);
        if (q.isEmpty() && l.kind() != ListSetting.Kind.CHOICE) return List.of();
        if (l.maxSize() > 0 && l.items().size() >= l.maxSize()) return List.of(); // full
        String key = l.getName() + "|" + q + "|" + l.get();
        if (key.equals(sugKey)) return sugCache;
        List<String> have = l.items(), starts = new ArrayList<>(), contains = new ArrayList<>();
        for (String id : l.universe()) {
            if (!l.ordered() && have.contains(id)) continue;
            if (id.startsWith(q)) starts.add(id);
            else if (id.contains(q)) contains.add(id);
        }
        starts.addAll(contains);
        int max = q.isEmpty() ? 4 : 6; // untyped choice lists: a short preview, leave room for the list
        sugCache = new ArrayList<>(starts.subList(0, Math.min(max, starts.size())));
        sugKey = key;
        return sugCache;
    }

    private void addListEntry(String id) {
        if (listEditing.add(id)) { listInput = ""; sugKey = null; }
        else listInvalidAt = System.currentTimeMillis();
    }

    /** Enter: the exact id if valid, else the top suggestion. */
    private void submitListInput() {
        String q = ListSetting.normalize(listInput);
        if (!q.isEmpty() && listEditing.valid(q)) { addListEntry(q); return; }
        List<String> s = suggestions(listEditing);
        if (!s.isEmpty()) addListEntry(s.get(0));
        else listInvalidAt = System.currentTimeMillis();
    }

    /** Dev GUI test only. */
    public String debugSearch() { return search; }

    /** Dev GUI test only: open a list editor with some typed text. */
    public void debugList(Module owner, ListSetting l, String input) { openList(owner, l); listInput = input; }

    /** Copy / Paste just this module's settings as a code (share with friends, or between profiles). */
    private void drawShareRow(GuiGraphicsExtractor ctx, Module m, float x, float y, float w, int clipTop, int clipBottom) {
        float bw = (w - 4) / 2f;
        button(ctx, "Copy", x, y, bw, SETTING_H - 2, clipTop, clipBottom, () -> {
            try {
                minecraft.keyboardHandler.setClipboard(Prism.config().exportModules(List.of(m)));
                tooltipFlash(m.getName() + " settings copied");
            } catch (Exception e) { tooltipFlash("Copy failed"); }
        });
        button(ctx, "Paste", x + bw + 4, y, bw, SETTING_H - 2, clipTop, clipBottom, () -> {
            try {
                List<String> done = Prism.config().importModules(minecraft.keyboardHandler.getClipboard(), java.util.Set.of(m.getName()));
                tooltipFlash(done.isEmpty() ? "Code has no " + m.getName() + " settings" : m.getName() + " settings pasted");
            } catch (Exception e) { tooltipFlash("Clipboard has no module code"); }
        });
    }

    /** Short message for the module-row buttons, shown in the tooltip strip at the bottom. */
    private void tooltipFlash(String msg) {
        flash(msg);
        moduleFlash = msg;
        moduleFlashAt = System.currentTimeMillis();
    }

    private String moduleFlash;
    private long moduleFlashAt;

    /** True while a text field / key bind / list editor owns the keyboard (no walking then). */
    public boolean capturesKeys() {
        return searchFocused || editing != null || listening != null || newProfile != null || listEditing != null;
    }

    /** Dev GUI test only: 0 closes, 1 Config, 2 Profiles. */
    public void debugMenu(int which) { openMenu(which == 1 ? Menu.CONFIG : which == 2 ? Menu.PROFILES : which == 3 ? Menu.KEYBINDS : Menu.NONE); }

    private void openMenu(Menu m) {
        menu = m;
        menuAnim = 0;
        menuScroll = 0;
        newProfile = null;
        deleteArmed = null;
        if (m == Menu.PROFILES) profileCache = Prism.config().list();
    }

    private void drawMenu(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        if (menu == Menu.NONE) return;
        menuAnim = approach(menuAnim, 1f, delta);
        float w = menu == Menu.CONFIG ? 172 : menu == Menu.KEYBINDS ? 190 : 150;
        float x = Mth.clamp(barX + barW - w, 4, width - w - 4), y = barY + barH + 4;
        float content = (menu == Menu.CONFIG ? configHeight() : menu == Menu.KEYBINDS ? keybindsHeight() : profilesHeight()) + PAD * 2;
        float maxH = Math.max(40, height - y - 8);
        float h = Math.min(content, maxH) * easeOut(menuAnim);
        menuScroll = Mth.clamp(menuScroll, 0, Math.max(0, content - maxH));
        menuX = x; menuY = y; menuW = w; menuH = h;

        ctx.nextStratum(); // always above the category panels
        Glass.panel(ctx, x, y, w, h, menuStyle());
        hits.add(new Hit(x, y, w, h, (b, cx, cy) -> {}, null, null)); // don't click through to panels
        if (h < 6) return;
        int clipTop = (int) y + 2, clipBottom = (int) (y + h - 2);
        ctx.enableScissor((int) x, clipTop, (int) (x + w), clipBottom);
        float cy = y + PAD - menuScroll;
        if (menu == Menu.CONFIG) drawConfigMenu(ctx, x + 4, cy, w - 8, mx, my, clipTop, clipBottom);
        else if (menu == Menu.KEYBINDS) drawKeybindsMenu(ctx, x + 4, cy, w - 8, mx, my, clipTop, clipBottom);
        else drawProfilesMenu(ctx, x + 4, cy, w - 8, mx, my, clipTop, clipBottom);
        ctx.disableScissor();
    }

    private float keybindsHeight() { return Prism.modules().get(dev.prismglass.module.client.KeyActions.class).actions().size() * MODULE_H + 4; }

    /** Keybinds: one row per action; left click = press a key, right click = unbind. */
    private void drawKeybindsMenu(GuiGraphicsExtractor ctx, float x, float y, float w, int mx, int my, int clipTop, int clipBottom) {
        for (var a : Prism.modules().get(dev.prismglass.module.client.KeyActions.class).actions()) {
            boolean hover = mouseIn(x, y, w, MODULE_H) && my >= clipTop && my <= clipBottom;
            if (hover) {
                fill(ctx, (int) x, (int) y, (int) (x + w), (int) (y + MODULE_H), 0x18FFFFFF);
                tooltip = a.description() + "  (left click: set key, right click: clear)";
            }
            ctx.text(font, a.name(), (int) x + 6, (int) y + 4, TEXT, false);
            String key = listening == a.key() ? "Press a key..." : KeyUtil.name(a.key().get());
            float kw = font.width(key) + 10, kx = x + w - kw - 4;
            Glass.rounded(ctx, kx, y + 2, kw, MODULE_H - 4, 3, listening == a.key() ? ColorUtil.withAlpha(theme.accent.color(), 150) : 0x30FFFFFF, 0x18FFFFFF);
            ctx.text(font, key, (int) kx + 5, (int) y + 4, listening == a.key() ? TEXT : DIM, false);
            if (y + MODULE_H >= clipTop && y <= clipBottom) {
                var k = a.key();
                hits.add(new Hit(x, y, w, MODULE_H, (b, cx, cy) -> { if (b == 1) k.set(KeyUtil.NONE); else listening = k; }, null, null));
            }
            y += MODULE_H;
        }
    }

    private float configHeight() { return 4 * 20 + 14 + SETTING_H + settingsHeight(theme); }

    private void drawConfigMenu(GuiGraphicsExtractor ctx, float x, float y, float w, int mx, int my, int clipTop, int clipBottom) {
        String[] names = {"Save", "Reload", "Reset layout", "Open folder", "Export (copy)", "Import (paste)", "Changelog", "HUD editor"};
        Runnable[] actions = {
            () -> { Prism.config().save(); flash("Saved " + Prism.config().getActive()); },
            () -> flash(Prism.config().loadProfile(Prism.config().getActive()) ? "Reloaded " + Prism.config().getActive() : "Nothing saved yet"),
            () -> { resetLayout(); flash("Layout reset"); },
            () -> net.minecraft.util.Util.getPlatform().openPath(Prism.config().folder()),
            () -> {
                try {
                    minecraft.keyboardHandler.setClipboard(Prism.config().exportCode(Prism.config().getActive()));
                    flash("Code copied - paste it to a friend");
                } catch (Exception e) { flash("Export failed"); }
            },
            () -> {
                try {
                    String n = Prism.config().importCode(minecraft.keyboardHandler.getClipboard(), "shared");
                    Prism.config().switchTo(n);
                    flash("Imported as " + n);
                } catch (Exception e) { flash("Clipboard has no Prism code"); }
            },
            () -> minecraft.setScreen(new ChangelogScreen(this)),
            this::openHudEditor,
        };
        float bw = (w - 4) / 2f, bh = 16;
        for (int i = 0; i < names.length; i++) {
            button(ctx, names[i], x + (i % 2) * (bw + 4), y + (i / 2) * (bh + 4), bw, bh, clipTop, clipBottom, actions[i]);
        }
        y += 4 * (bh + 4);
        boolean fresh = status != null && System.currentTimeMillis() - statusAt < 2500;
        String line = fresh ? status : "Profile: " + Prism.config().getActive();
        ctx.text(font, line, (int) x + 3, (int) y + 3, fresh ? theme.accent.color() : FAINT, false);
        y += 14;
        fill(ctx, (int) x + 2, (int) y, (int) (x + w - 2), (int) y + 1, 0x22FFFFFF);
        ctx.text(font, "ClickGUI", (int) x + 3, (int) y + 4, TEXT, true);
        y += SETTING_H;
        for (Setting<?> st : theme.getSettings()) {
            if (st.isVisible()) y = drawSetting(ctx, theme, st, x, y, w, mx, my, clipTop, clipBottom);
        }
    }

    private float profilesHeight() { return (profileCache.size() + 1) * MODULE_H + 4; }

    private void drawProfilesMenu(GuiGraphicsExtractor ctx, float x, float y, float w, int mx, int my, int clipTop, int clipBottom) {
        String active = Prism.config().getActive();
        int acc = theme.accent.color();
        for (String name : profileCache) {
            boolean isActive = name.equals(active), armed = name.equals(deleteArmed);
            float top = Math.max(y, clipTop), bottom = Math.min(y + MODULE_H, clipBottom);
            boolean hover = bottom > top && mouseIn(x, top, w, bottom - top);
            if (isActive) Glass.rounded(ctx, x, y + 1, w, MODULE_H - 2, 4, ColorUtil.withAlpha(acc, 150), ColorUtil.withAlpha(acc, 70));
            else if (armed) Glass.rounded(ctx, x, y + 1, w, MODULE_H - 2, 4, 0x70FF4A4A, 0x40FF4A4A);
            else if (hover) fill(ctx, (int) x, (int) y + 1, (int) (x + w), (int) (y + MODULE_H - 1), 0x18FFFFFF);
            String label = armed ? "Right click again: delete" : font.plainSubstrByWidth(name, (int) w - 22);
            ctx.text(font, label, (int) x + 6, (int) y + 4, isActive ? TEXT : armed ? 0xFFFFB0B0 : DIM, isActive);
            if (isActive) ctx.text(font, "\u2714", (int) (x + w - 12), (int) y + 4, TEXT, false);
            if (hover) tooltip = isActive ? "Active profile (saved when the GUI closes)." : "Left click: load.  Right click twice: delete.";
            if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, (b, cx, cy) -> {
                if (isActive) return;
                if (b == 0) switchProfile(name);
                else if (b == 1) {
                    if (name.equals(armedPrev)) {
                        if (Prism.config().delete(name)) flash("Deleted " + name);
                        profileCache = Prism.config().list();
                    } else deleteArmed = name;
                }
            }, null, null));
            y += MODULE_H;
        }
        if (newProfile != null) {
            Glass.rounded(ctx, x, y + 1, w, MODULE_H - 2, 4, 0x30FFFFFF, 0x20FFFFFF);
            String v = newProfile.isEmpty() ? "Type a name, Enter" : newProfile + (blink() ? "_" : "");
            ctx.text(font, v, (int) x + 6, (int) y + 4, newProfile.isEmpty() ? FAINT : TEXT, false);
        } else {
            button(ctx, "+ New profile", x, y + 1, w, MODULE_H - 2, clipTop, clipBottom, () -> newProfile = "");
            if (mouseIn(x, y, w, MODULE_H)) tooltip = "Saves the current settings as a new profile.";
        }
    }

    private void switchProfile(String name) {
        try {
            Prism.config().switchTo(name);
            flash("Loaded " + name);
        } catch (Exception e) {
            Prism.LOG.error("Profile switch failed", e);
            flash("Couldn't load " + name);
        }
        profileCache = Prism.config().list();
    }

    private void createProfile() {
        String name = dev.prismglass.config.ConfigManager.clean(newProfile == null ? "" : newProfile.trim());
        newProfile = null;
        if (name.isEmpty()) return;
        switchProfile(name); // saves the old profile; a new name then stores the current settings under it
    }

    private void button(GuiGraphicsExtractor ctx, String label, float x, float y, float w, float h,
                        int clipTop, int clipBottom, Runnable action) {
        float top = Math.max(y, clipTop), bottom = Math.min(y + h, clipBottom);
        boolean hover = bottom > top && mouseIn(x, top, w, bottom - top);
        Glass.rounded(ctx, x, y, w, h, 4, hover ? 0x44FFFFFF : 0x26FFFFFF, hover ? 0x30FFFFFF : 0x14FFFFFF);
        ctx.text(font, label, (int) (x + w / 2 - font.width(label) / 2f), (int) (y + (h - 8) / 2 + 1), TEXT, false);
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, (b, cx, cy) -> { if (b == 0) action.run(); }, null, null));
    }

    /** Panel style with a heavier tint so the dropdown reads cleanly over the panels under it. */
    /** ctx.fill, snapped to the block grid in Blocky mode. */
    private static void fill(GuiGraphicsExtractor ctx, int x0, int y0, int x1, int y1, int color) {
        if (Glass.pixel > 0) Glass.rect(ctx, x0, y0, x1, y1, color);
        else ctx.fill(x0, y0, x1, y1, color);
    }

    private Glass.Style menuStyle() {
        Glass.Style s = styleWith(1f);
        return new Glass.Style(s.tint(), Math.min(1f, s.opacity() * 1.8f + 0.2f), s.radius(), s.shadow(), s.sheen(), s.rimStrength(),
            s.liquid(), s.refraction(), s.dispersion(), s.blur() + 1.5f, Math.max(s.tintStrength(), 0.7f));
    }

    private void flash(String msg) { status = msg; statusAt = System.currentTimeMillis(); }

    private boolean mouseIn(float x, float y, float w, float h) {
        return lastMouseX >= x && lastMouseX < x + w && lastMouseY >= y && lastMouseY < y + h;
    }

    private static boolean inRect(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void drawPanel(GuiGraphicsExtractor ctx, Category cat, int mx, int my, float delta, float alpha) {
        PanelState ps = PANELS.get(cat);
        List<Module> modules = filtered(cat);
        if (!search.isEmpty() && modules.isEmpty()) return;

        float w = theme.panelWidth.getFloat();
        float target = ps.open ? 1f : 0f;
        ps.openAnim = approach(ps.openAnim, target, delta);

        // content height (with animations) to size the glass body
        float contentH = 0;
        for (Module m : modules) contentH += MODULE_H + settingsHeight(m) * anim(expandAnim, m);
        float maxH = Math.max(60, height - ps.y - HEADER_H - 12);
        float visibleH = Math.min(contentH, maxH) * easeOut(ps.openAnim);
        ps.scroll = Mth.clamp(ps.scroll, 0, Math.max(0, contentH - maxH));

        float x = ps.x, y = ps.y;
        Glass.panel(ctx, x, y, w, HEADER_H + visibleH + (visibleH > 0 ? PAD : 0), styleWith(alpha));

        // header: category tint wash + name + module count
        Glass.rounded(ctx, x + 2, y + 2, w - 4, HEADER_H - 3, theme.radius.getFloat() - 2,
            ColorUtil.withAlpha(cat.tint, 70), ColorUtil.withAlpha(cat.tint, 10));
        ctx.text(font, cat.title, (int) (x + 8), (int) (y + 6), TEXT, true);
        String count = String.valueOf(modules.stream().filter(Module::isEnabled).count()) + "/" + modules.size();
        ctx.text(font, count, (int) (x + w - 8 - font.width(count)), (int) (y + 6), DIM, false);
        hits.add(new Hit(x, y, w, HEADER_H, (b, cx, cy) -> {
            if (b == 0) { dragging = cat; dragOffX = (float) cx - ps.x; dragOffY = (float) cy - ps.y; }
            else if (b == 1) ps.open = !ps.open;
        }, null, null));

        if (visibleH <= 0.5f) return;
        int sx = (int) x, sy = (int) (y + HEADER_H), ex = (int) (x + w), ey = (int) (y + HEADER_H + visibleH + 1);
        ctx.enableScissor(sx, sy, ex, ey);
        float cy = y + HEADER_H - ps.scroll;
        for (Module m : modules) {
            cy = drawModule(ctx, m, x, cy, w, mx, my, delta, sy, ey);
        }
        ctx.disableScissor();
    }

    private float drawModule(GuiGraphicsExtractor ctx, Module m, float x, float y, float w, int mx, int my, float delta, int clipTop, int clipBottom) {
        float en = anim(enableAnim, m);
        enableAnim.put(m, approach(en, m.isEnabled() ? 1f : 0f, delta));
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + MODULE_H && my >= clipTop && my < clipBottom;

        if (en > 0.01f) {
            int acc = theme.accent.color((int) y * 8);
            Glass.rounded(ctx, x + 3, y + 1, w - 6, MODULE_H - 2, 4,
                ColorUtil.fade(ColorUtil.withAlpha(acc, 150), en), ColorUtil.fade(ColorUtil.withAlpha(acc, 70), en));
        }
        if (hover) fill(ctx, (int) x + 3, (int) y + 1, (int) (x + w - 3), (int) (y + MODULE_H - 1), 0x18FFFFFF);

        String name = listeningFor(m) ? "Press a key..." : m.getName();
        ctx.text(font, name, (int) (x + 8), (int) (y + 4), m.isEnabled() ? TEXT : DIM, m.isEnabled());
        String arrow = EXPANDED.contains(m) ? "-" : "+";
        if (!m.getSettings().isEmpty()) ctx.text(font, arrow, (int) (x + w - 12), (int) (y + 4), FAINT, false);

        if (y + MODULE_H > clipTop && y < clipBottom) {
            hits.add(new Hit(x, Math.max(y, clipTop), w, Math.min(y + MODULE_H, clipBottom) - Math.max(y, clipTop), (b, cx, cy) -> {
                if (b == 0) m.toggle();
                else if (b == 1) { if (!EXPANDED.remove(m)) EXPANDED.add(m); }
                else if (b == 2) listening = m.bind;
            }, null, m.getDescription()));
            if (hover) tooltip = m.getDescription();
        }
        y += MODULE_H;

        float ex = approach(anim(expandAnim, m), EXPANDED.contains(m) ? 1f : 0f, delta);
        expandAnim.put(m, ex);
        if (ex <= 0.01f) return y;

        float full = settingsHeight(m);
        float shown = full * ex;
        float top = y;
        fill(ctx, (int) x + 4, (int) top, (int) (x + 5), (int) (top + shown), ColorUtil.withAlpha(theme.accent.color(), 90));
        ctx.enableScissor((int) x, (int) Math.max(top, clipTop), (int) (x + w), (int) Math.min(top + shown, clipBottom));
        for (Setting<?> s : m.getSettings()) {
            if (!s.isVisible()) continue;
            y = drawSetting(ctx, m, s, x + 7, y, w - 11, mx, my, Math.max((int) top, clipTop), Math.min((int) (top + shown), clipBottom));
        }
        drawShareRow(ctx, m, x + 7, y + 1, w - 11, Math.max((int) top, clipTop), Math.min((int) (top + shown), clipBottom));
        ctx.disableScissor(); // pops back to the panel scissor (DrawContext keeps a stack)
        return top + shown;
    }

    private float drawSetting(GuiGraphicsExtractor ctx, Module m, Setting<?> s, float x, float y, float w, int mx, int my, int clipTop, int clipBottom) {
        float h = rowHeight(s);
        boolean visible = y + h > clipTop && y < clipBottom;
        boolean hover = visible && mx >= x && mx < x + w && my >= Math.max(y, clipTop) && my < Math.min(y + h, clipBottom);
        if (hover) { fill(ctx, (int) x, (int) y, (int) (x + w), (int) (y + h), 0x12FFFFFF); tooltip = s.getDescription(); }
        int ty = (int) (y + 3);

        switch (s) {
            case BoolSetting b -> {
                ctx.text(font, b.getName(), (int) x + 3, ty, DIM, false);
                float tx = x + w - 20, tyy = y + 3;
                int acc = theme.accent.color();
                float t = toggleProgress(b);                 // 0 = off .. 1 = on, animated
                float e = t * t * (3 - 2 * t);               // smoothstep easing
                int top = ColorUtil.lerp(0x55FFFFFF, ColorUtil.withAlpha(acc, 220), e);
                int bottom = ColorUtil.lerp(0x30FFFFFF, ColorUtil.withAlpha(acc, 140), e);
                Glass.pill(ctx, tx, tyy, 16, 8, top, bottom, 0.8f);
                // knob slides across and stretches slightly mid-travel, like an iOS switch
                float stretch = 2.5f * (float) Math.sin(Math.PI * e);
                float knobW = 6 + stretch;
                float knobX = tx + 1 + (16 - 2 - knobW) * e;
                Glass.pill(ctx, knobX, tyy + 1, knobW, 6, 0xF0FFFFFF, 0xC0DDE6F0, 0.5f);
                if (visible) click(x, y, w, h, clipTop, clipBottom, (bt, cx, cy) -> b.toggle(), s);
            }
            case NumberSetting n -> {
                ctx.text(font, n.getName(), (int) x + 3, ty, DIM, false);
                String v = n.display();
                ctx.text(font, v, (int) (x + w - 3 - font.width(v)), ty, TEXT, false);
                float bx = x + 3, bw = w - 6, by = y + 13;
                float pct = (float) ((n.get() - n.getMin()) / (n.getMax() - n.getMin()));
                Glass.rounded(ctx, bx, by, bw, 3, 1.5f, 0x40FFFFFF, 0x30FFFFFF);
                int acc = theme.accent.color();
                Glass.rounded(ctx, bx, by, Math.max(3, bw * pct), 3, 1.5f, ColorUtil.withAlpha(acc, 255), ColorUtil.withAlpha(acc, 190));
                Glass.pill(ctx, bx + bw * pct - 3, by - 1.5f, 6, 6, 0xFFFFFFFF, 0xD0DDE6F0, 0.5f);
                if (visible) {
                    Drag drag = (dx, dy) -> n.set(n.getMin() + (n.getMax() - n.getMin()) * Mth.clamp((dx - bx) / bw, 0, 1));
                    hits.add(new Hit(x, Math.max(y, clipTop), w, Math.min(y + h, clipBottom) - Math.max(y, clipTop),
                        (bt, cx, cy) -> { if (bt == 0) { activeDrag = drag; drag.drag(cx, cy); } else if (bt == 1) n.reset(); }, drag, s.getDescription()));
                }
            }
            case ModeSetting md -> {
                ctx.text(font, md.getName(), (int) x + 3, ty, DIM, false);
                String v = "‹ " + md.get() + " ›";
                ctx.text(font, v, (int) (x + w - 3 - font.width(v)), ty, theme.accent.color(), false);
                if (visible) click(x, y, w, h, clipTop, clipBottom, (bt, cx, cy) -> md.cycle(bt == 0), s);
            }
            case ColorSetting c -> {
                ctx.text(font, c.getName(), (int) x + 3, ty, DIM, false);
                Glass.pill(ctx, x + w - 22, y + 3, 18, 8, c.color(), c.color(), 1f);
                if (visible) click(x, y, w, SETTING_H, clipTop, clipBottom, (bt, cx, cy) -> {
                    if (bt == 1) { c.setRainbow(!c.isRainbow()); return; }
                    if (!EXPANDED_COLORS.remove(c)) EXPANDED_COLORS.add(c);
                }, s);
                if (EXPANDED_COLORS.contains(c)) drawColorPicker(ctx, c, x, y + SETTING_H, w, clipTop, clipBottom);
            }
            case KeySetting k -> {
                ctx.text(font, k.getName(), (int) x + 3, ty, DIM, false);
                String v = listening == k ? "..." : KeyUtil.name(k.get());
                ctx.text(font, v, (int) (x + w - 3 - font.width(v)), ty, TEXT, false);
                if (visible) click(x, y, w, h, clipTop, clipBottom, (bt, cx, cy) -> {
                    if (bt == 1) k.set(KeyUtil.NONE); else listening = k;
                }, s);
            }
            case ListSetting ls -> {
                ctx.text(font, ls.getName(), (int) x + 3, ty, DIM, false);
                int n = ls.items().size();
                String v = n + (n == 1 ? " entry" : " entries") + " \u203a";
                ctx.text(font, v, (int) (x + w - 3 - font.width(v)), ty, theme.accent.color(), false);
                if (visible) click(x, y, w, h, clipTop, clipBottom, (bt, cx, cy) -> openList(m, ls), s);
            }
            case TextSetting t -> {
                ctx.text(font, t.getName(), (int) x + 3, ty, DIM, false);
                String v = t.get() + (editing == t && blink() ? "_" : "");
                v = font.plainSubstrByWidth(v, (int) (w * 0.55f));
                ctx.text(font, v, (int) (x + w - 3 - font.width(v)), ty, editing == t ? TEXT : DIM, false);
                if (visible) click(x, y, w, h, clipTop, clipBottom, (bt, cx, cy) -> editing = editing == t ? null : t, s);
            }
            default -> ctx.text(font, s.getName() + ": " + s.display(), (int) x + 3, ty, DIM, false);
        }
        return y + h;
    }

    private void drawColorPicker(GuiGraphicsExtractor ctx, ColorSetting c, float x, float y, float w, int clipTop, int clipBottom) {
        float[] hsb = Color.RGBtoHSB(ColorUtil.red(c.get()), ColorUtil.green(c.get()), ColorUtil.blue(c.get()), null);
        float alpha = ColorUtil.alpha(c.get()) / 255f;
        String[] labels = {"Hue", "Sat", "Bri", "Alpha"};
        float[] values = {hsb[0], hsb[1], hsb[2], alpha};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            float ry = y + i * SETTING_H;
            ctx.text(font, labels[i], (int) x + 6, (int) ry + 3, FAINT, false);
            float bx = x + 34, bw = w - 40, by = ry + 5;
            // gradient track
            int left, right;
            switch (i) {
                case 0 -> { left = 0xFFFF0000; right = 0xFFFF0000; }
                case 1 -> { left = 0xFFFFFFFF; right = 0xFF000000 | Color.HSBtoRGB(hsb[0], 1, hsb[2]); }
                case 2 -> { left = 0xFF000000; right = 0xFF000000 | Color.HSBtoRGB(hsb[0], hsb[1], 1); }
                default -> { left = 0x00FFFFFF; right = c.get() | 0xFF000000; }
            }
            if (i == 0) {
                for (int s = 0; s < 24; s++) {
                    int col = 0xFF000000 | Color.HSBtoRGB((s + 0.5f) / 24f, 1, 1);
                    fill(ctx, (int) (bx + bw * s / 24f), (int) by, (int) Math.ceil(bx + bw * (s + 1) / 24f), (int) by + 4, col);
                }
            } else {
                for (int s = 0; s < 8; s++) {
                    int col = ColorUtil.lerp(left, right, (s + 0.5f) / 8f);
                    fill(ctx, (int) (bx + bw * s / 8f), (int) by, (int) (bx + bw * (s + 1) / 8f), (int) by + 4, col);
                }
            }
            Glass.pill(ctx, bx + bw * values[i] - 2.5f, by - 1.5f, 5, 7, 0xFFFFFFFF, 0xD0DDE6F0, 0.5f);
            if (ry + SETTING_H > clipTop && ry < clipBottom) {
                Drag drag = (dx, dy) -> {
                    float v = (float) Mth.clamp((dx - bx) / bw, 0, 1);
                    float[] cur = Color.RGBtoHSB(ColorUtil.red(c.get()), ColorUtil.green(c.get()), ColorUtil.blue(c.get()), null);
                    int a = ColorUtil.alpha(c.get());
                    if (idx == 0) cur[0] = v; else if (idx == 1) cur[1] = v; else if (idx == 2) cur[2] = v; else a = Math.round(v * 255);
                    c.set(ColorUtil.withAlpha(Color.HSBtoRGB(cur[0], cur[1], cur[2]), a));
                };
                hits.add(new Hit(x, ry, w, SETTING_H, (bt, cx, cy) -> { activeDrag = drag; drag.drag(cx, cy); }, drag, "Drag to adjust. Right click the swatch for rainbow."));
            }
        }
    }

    private void click(float x, float y, float w, float h, int clipTop, int clipBottom, Click c, Setting<?> s) {
        float top = Math.max(y, clipTop), bottom = Math.min(y + h, clipBottom);
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, c, null, s.getDescription()));
    }

    private void drawTooltip(GuiGraphicsExtractor ctx, String text) {
        float w = font.width(text) + 16;
        float x = width / 2f - w / 2f, y = height - 26;
        Glass.panel(ctx, x, y, w, 16, styleWith(1f));
        ctx.text(font, text, (int) (x + 8), (int) (y + 4), TEXT, false);
    }

    // ---- layout helpers -------------------------------------------------------------------------

    private List<Module> filtered(Category cat) {
        List<Module> all = new java.util.ArrayList<>(Prism.modules().byCategory(cat));
        all.removeIf(m -> m instanceof dev.prismglass.module.client.KeyActions); // shown in the Keybinds tab instead
        if (search.isEmpty()) return all;
        String q = search.toLowerCase(Locale.ROOT);
        List<Module> out = new ArrayList<>();
        for (Module m : all) if (m.getName().toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        return out;
    }

    private float settingsHeight(Module m) {
        float h = SETTING_H + 2; // Copy / Paste row
        for (Setting<?> s : m.getSettings()) if (s.isVisible()) h += rowHeight(s);
        return h;
    }

    private float rowHeight(Setting<?> s) {
        if (s instanceof NumberSetting) return SLIDER_H;
        if (s instanceof ColorSetting c && EXPANDED_COLORS.contains(c)) return SETTING_H * 5;
        return SETTING_H;
    }

    private Glass.Style styleWith(float alpha) {
        return theme.panelStyle().withAlpha(alpha);
    }

    private boolean listeningFor(Module m) { return listening == m.bind; }

    private static float anim(Map<Module, Float> map, Module m) { return map.getOrDefault(m, 0f); }

    /** Frame-rate independent animation of a toggle switch toward its value (exponential ease). */
    private float toggleProgress(BoolSetting b) {
        float target = b.get() ? 1f : 0f;
        Float cur = toggleAnim.get(b);
        if (cur == null) { toggleAnim.put(b, target); return target; } // first sight: no animation
        float k = 1f - (float) Math.exp(-toggleDt * 16f * theme.animSpeed.getFloat());
        float next = cur + (target - cur) * k;
        if (Math.abs(next - target) < 0.002f) next = target;
        toggleAnim.put(b, next);
        return next;
    }

    /** Frame-time based exponential ease, so panels/highlights glide the same at any FPS. */
    private float approach(float cur, float target, float delta) {
        float k = 1f - (float) Math.exp(-toggleDt * 12f * theme.animSpeed.getFloat());
        float next = cur + (target - cur) * k;
        return Math.abs(next - target) < 0.003f ? target : next;
    }

    private static float easeOut(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }

    private static boolean blink() { return (System.currentTimeMillis() / 500) % 2 == 0; }

    // ---- input -----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = event.button();
        if (listEditing != null) {
            // modal: only the editor's own hits (and the outside-click catcher) count
            for (int i = hits.size() - 1; i >= Math.max(0, popupHitStart); i--) {
                Hit h = hits.get(i);
                if (h.contains(mx, my) && h.click() != null) { h.click().click(button, mx, my); break; }
            }
            return true;
        }
        if (listening != null) {
            if (button >= 2) { listening.set(KeyUtil.MOUSE_OFFSET + button); listening = null; return true; }
            listening = null;
        }
        editing = null;
        armedPrev = deleteArmed;
        deleteArmed = null;
        searchFocused = false;
        if (menu != Menu.NONE && !inRect(mx, my, menuX, menuY, menuW, menuH) && !inRect(mx, my, barX, barY, barW, barH)) {
            openMenu(Menu.NONE);
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(mx, my) && h.click() != null) {
                h.click().click(button, mx, my);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        double mx = event.x(), my = event.y();
        if (dragging != null) {
            PanelState ps = PANELS.get(dragging);
            ps.x = Mth.clamp((float) mx - dragOffX, 0, width - 40);
            ps.y = Mth.clamp((float) my - dragOffY, 0, height - HEADER_H);
            return true;
        }
        if (activeDrag != null) { activeDrag.drag(mx, my); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        dragging = null;
        activeDrag = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (listEditing != null) { listScroll -= (float) vertical * 18; return true; }
        if (menu != Menu.NONE && inRect(mx, my, menuX, menuY, menuW, menuH)) { menuScroll -= (float) vertical * 18; return true; }
        float w = theme.panelWidth.getFloat();
        for (PanelState ps : PANELS.values()) {
            if (mx >= ps.x && mx < ps.x + w && my >= ps.y) { ps.scroll -= (float) vertical * 18; return true; }
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (listEditing != null) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> { if (!listInput.isEmpty()) listInput = listInput.substring(0, listInput.length() - 1); }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> submitListInput();
                case GLFW.GLFW_KEY_TAB -> { List<String> sg = suggestions(listEditing); if (!sg.isEmpty()) listInput = sg.get(0); }
                case GLFW.GLFW_KEY_ESCAPE -> { if (!listInput.isEmpty()) listInput = ""; else closeList(); }
                default -> {}
            }
            return true;
        }
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) listening = null;
            else {
                listening.set(key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE ? KeyUtil.NONE : key);
                listening = null;
            }
            return true;
        }
        if (editing != null) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> { String v = editing.get(); if (!v.isEmpty()) editing.set(v.substring(0, v.length() - 1)); }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_ESCAPE -> editing = null;
                default -> {}
            }
            return true;
        }
        if (newProfile != null) {
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> { if (!newProfile.isEmpty()) newProfile = newProfile.substring(0, newProfile.length() - 1); }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> createProfile();
                case GLFW.GLFW_KEY_ESCAPE -> newProfile = null;
                default -> {}
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && menu != Menu.NONE) { openMenu(Menu.NONE); return true; }
        if (searchFocused) {
            lastSearchInput = System.currentTimeMillis();
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> { if (!search.isEmpty()) search = search.substring(0, search.length() - 1); }
                case GLFW.GLFW_KEY_ESCAPE -> { search = ""; searchFocused = false; }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> searchFocused = false;
                default -> {}
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && !search.isEmpty()) { search = ""; return true; }
        boolean justOpened = System.currentTimeMillis() - openedAt < 150;
        if (!justOpened && (key == theme.bind.get() || key == GLFW.GLFW_KEY_ESCAPE)) { onClose(); return true; }
        // search isn't focused: the key is a module bind, same as in game
        if (!justOpened && key != GLFW.GLFW_KEY_ESCAPE) { Prism.modules().onKey(key, true); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyEvent event) {
        if (!searchFocused && listening == null && editing == null && newProfile == null && listEditing == null) Prism.modules().onKey(event.key(), false);
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!Character.isBmpCodePoint(event.codepoint())) return false;
        char chr = (char) event.codepoint();
        if (listEditing != null) {
            if (!Character.isISOControl(chr) && chr != ',' && chr != ' ' && listInput.length() < 48) listInput += Character.toLowerCase(chr);
            return true;
        }
        if (editing != null) { editing.set(editing.get() + chr); return true; }
        if (newProfile != null) {
            if (chr < 128 && (Character.isLetterOrDigit(chr) || chr == '_' || chr == '-') && newProfile.length() < 24) newProfile += chr;
            return true;
        }
        if (searchFocused && listening == null && !Character.isISOControl(chr) && search.length() < 24) {
            search += chr;
            lastSearchInput = System.currentTimeMillis();
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        Prism.config().save();
        super.onClose();
    }

    // ---- StreamProof cursor -----------------------------------------------------------------------------

    /** Classic arrow, 1 char = 1 screen pixel: B outline, W fill. */
    private static final String[] ARROW = {
        "B", "BB", "BWB", "BWWB", "BWWWB", "BWWWWB", "BWWWWWB", "BWWWWWWB", "BWWWWWWWB", "BWWWWWWWWB", "BWWWWWWWWWB",
        "BWWWWWWBBBBB", "BWWWBWWB", "BWWBBWWB", "BWB BWWB", "BB   BWWB", "B    BWWB", "      BWWB", "       BB"};

    private void updateCursor() {
        long handle = minecraft.getWindow().handle();
        if (dev.prismglass.streamproof.Overlay.wantsSoftCursor()) {
            if (GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_HIDDEN)
                GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
            cursorHidden = true;
        } else restoreCursor();
    }

    private void restoreCursor() {
        if (!cursorHidden) return;
        cursorHidden = false;
        long handle = minecraft.getWindow().handle();
        if (GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_HIDDEN)
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }

    /** Drawn in screen pixels at the exact (sub-GUI-pixel) mouse position, on top of everything. */
    private void drawSoftCursor(GuiGraphicsExtractor ctx) {
        var window = minecraft.getWindow();
        float x = (float) minecraft.mouseHandler.getScaledXPos(window), y = (float) minecraft.mouseHandler.getScaledYPos(window);
        float px = Math.max(1, Math.round(window.getHeight() / 1080f)) / (float) window.getGuiScale();
        ctx.nextStratum();
        ctx.pose().pushMatrix();
        ctx.pose().translate(x, y);
        ctx.pose().scale(px, px);
        for (int row = 0; row < ARROW.length; row++) {
            String line = ARROW[row];
            for (int col = 0; col < line.length(); ) {
                char c = line.charAt(col);
                int end = col;
                while (end < line.length() && line.charAt(end) == c) end++;
                if (c != ' ') ctx.fill(col, row, end, row + 1, c == 'B' ? 0xFF000000 : 0xFFFFFFFF);
                col = end;
            }
        }
        ctx.pose().popMatrix();
    }

    @Override
    public void removed() {
        restoreCursor();
        super.removed();
    }

    // ---- persistence -------------------------------------------------------------------------------

    public static JsonObject saveLayout() {
        JsonObject o = new JsonObject();
        for (var e : PANELS.entrySet()) {
            JsonObject p = new JsonObject();
            p.addProperty("x", e.getValue().x);
            p.addProperty("y", e.getValue().y);
            p.addProperty("open", e.getValue().open);
            o.add(e.getKey().name(), p);
        }
        return o;
    }

    public static void loadLayout(JsonObject o) {
        for (var e : PANELS.entrySet()) {
            if (!o.has(e.getKey().name())) continue;
            JsonObject p = o.getAsJsonObject(e.getKey().name());
            e.getValue().x = p.get("x").getAsFloat();
            e.getValue().y = p.get("y").getAsFloat();
            e.getValue().open = p.get("open").getAsBoolean();
            e.getValue().openAnim = e.getValue().open ? 1f : 0f;
        }
    }
}
