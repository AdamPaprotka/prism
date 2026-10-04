package dev.prismglass.gui;

import dev.prismglass.Prism;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.module.client.Hud;
import dev.prismglass.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Drag HUD elements around. Left drag moves (snaps to the screen edges and centre lines), right click puts an
 * element back to its default spot, Esc goes back. The HUD draws itself under this screen with placeholders for
 * empty elements, so even an empty target card or counter bar can be placed.
 */
public class HudEditorScreen extends Screen {
    private static final float SNAP = 5, MARGIN = 4;

    private final @Nullable Screen parent;
    private final Hud hud;
    private @Nullable String dragging;
    private float offX, offY, dragW, dragH;
    private boolean snapX, snapY; // centre guides to show while dragging

    public HudEditorScreen(@Nullable Screen parent) {
        super(Component.literal("HUD editor"));
        this.parent = parent;
        this.hud = Prism.modules().get(Hud.class);
    }

    @Override
    protected void init() {
        Hud.editing = true;
        if (!hud.isEnabled()) hud.setEnabled(true);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x40080A12);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int accent = Prism.modules().get(ClickGui.class).accent.color();
        if (dragging != null && (snapX || snapY)) {
            int guide = ColorUtil.withAlpha(accent, 120);
            if (snapX) ctx.fill(width / 2, 0, width / 2 + 1, height, guide);
            if (snapY) ctx.fill(0, height / 2, width, height / 2 + 1, guide);
        }
        String hovered = dragging != null ? dragging : at(mouseX, mouseY);
        for (Map.Entry<String, float[]> e : hud.bounds().entrySet()) {
            float[] b = e.getValue();
            boolean hot = e.getKey().equals(hovered);
            int col = hot ? ColorUtil.withAlpha(accent, 230) : hud.isMoved(e.getKey()) ? 0x90FFFFFF : 0x50FFFFFF;
            outline(ctx, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, col);
            if (hot) {
                String name = e.getKey() + (hud.isMoved(e.getKey()) ? "" : " §7(default)");
                float ly = b[1] - 11 < 2 ? b[1] + b[3] + 3 : b[1] - 11;
                ctx.text(font, name, (int) b[0], (int) ly, 0xFFFFFFFF, true);
            }
        }
        String hint = "Drag to move  ·  Right click: reset  ·  Esc: done";
        ctx.text(font, "§7" + hint, (width - font.width(hint)) / 2, height / 2 - 34, 0xFFFFFFFF, true);
    }

    private static void outline(GuiGraphicsExtractor ctx, float x, float y, float w, float h, int c) {
        int x0 = (int) x, y0 = (int) y, x1 = (int) (x + w), y1 = (int) (y + h);
        ctx.fill(x0, y0, x1, y0 + 1, c);
        ctx.fill(x0, y1 - 1, x1, y1, c);
        ctx.fill(x0, y0 + 1, x0 + 1, y1 - 1, c);
        ctx.fill(x1 - 1, y0 + 1, x1, y1 - 1, c);
    }

    /** Topmost element under the mouse (drawn last = on top). */
    private @Nullable String at(double mx, double my) {
        List<String> names = new ArrayList<>(hud.bounds().keySet());
        for (int i = names.size() - 1; i >= 0; i--) {
            float[] b = hud.bounds().get(names.get(i));
            if (mx >= b[0] - 2 && mx <= b[0] + b[2] + 2 && my >= b[1] - 2 && my <= b[1] + b[3] + 2) return names.get(i);
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        String hit = at(event.x(), event.y());
        if (hit == null) return super.mouseClicked(event, doubleClick);
        if (event.button() == 1) {
            hud.resetPosition(hit);
            return true;
        }
        if (event.button() == 0) {
            float[] b = hud.bounds().get(hit);
            dragging = hit;
            offX = (float) event.x() - b[0];
            offY = (float) event.y() - b[1];
            dragW = b[2];
            dragH = b[3];
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(event, dx, dy);
        float x = (float) event.x() - offX, y = (float) event.y() - offY;
        // snap to the screen edges (with the usual margin) and the centre lines
        snapX = snapY = false;
        if (Math.abs(x - MARGIN) < SNAP) x = MARGIN;
        if (Math.abs(x + dragW - (width - MARGIN)) < SNAP) x = width - MARGIN - dragW;
        if (Math.abs(x + dragW / 2 - width / 2f) < SNAP) { x = width / 2f - dragW / 2; snapX = true; }
        if (Math.abs(y - MARGIN) < SNAP) y = MARGIN;
        if (Math.abs(y + dragH - (height - MARGIN)) < SNAP) y = height - MARGIN - dragH;
        if (Math.abs(y + dragH / 2 - height / 2f) < SNAP) { y = height / 2f - dragH / 2; snapY = true; }
        x = Math.max(0, Math.min(width - dragW, x));
        y = Math.max(0, Math.min(height - dragH, y));
        hud.setPosition(dragging, x, y, dragW, dragH, width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            snapX = snapY = false;
            Prism.config().save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        Prism.config().save();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        Hud.editing = false;
        super.removed();
    }
}
