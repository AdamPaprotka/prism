package dev.prismglass.module.render;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * Replaces the vanilla crosshair (CrosshairMixin cancels Gui.extractCrosshair while this is on) with a configurable
 * one. Dynamic opens the gap while you move and jump, Indicator draws the attack cooldown as a bar under it, and the
 * colour switches to Target while the crosshair is on a living entity.
 */
public class CustomCrosshair extends Module {
    public final ModeSetting shape = mode("Shape", "Cross", "Crosshair style.", "Cross", "Dot", "Circle", "T", "Plus");
    public final NumberSetting length = num("Length", 5, 1, 20, 1, "Arm length in GUI pixels.");
    public final NumberSetting gap = num("Gap", 2, 0, 12, 1, "Gap around the centre.");
    public final NumberSetting thickness = num("Thickness", 1, 1, 5, 1, "Line thickness.");
    public final ColorSetting color = color("Color", 0xFFFFFFFF, "Crosshair colour.");
    public final ColorSetting target = color("Target", 0xFFFF4D6A, "Colour while aiming at a living entity.");
    public final BoolSetting outline = bool("Outline", true, "Dark outline so it shows on any background.");
    public final BoolSetting centerDot = bool("CenterDot", false, "Add a dot in the middle.");
    public final BoolSetting dynamic = bool("Dynamic", true, "Gap opens while moving / in the air.");
    public final BoolSetting indicator = bool("Indicator", true, "Attack cooldown bar under the crosshair.");

    private float spread;

    public CustomCrosshair() { super("CustomCrosshair", "Your own crosshair: shapes, gap, colours, cooldown bar.", Category.RENDER); }

    /** Called by CrosshairMixin instead of the vanilla crosshair. */
    public void draw(GuiGraphicsExtractor g) {
        var p = mc.player;
        if (p == null) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        float want = 0;
        if (dynamic.get()) {
            double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo);
            want = (float) Math.min(6, speed * 18) + (p.onGround() ? 0 : 3);
        }
        spread += (want - spread) * 0.25f;
        int gp = gap.getInt() + Math.round(spread), len = length.getInt(), t = thickness.getInt();
        boolean onTarget = mc.crosshairPickEntity instanceof LivingEntity le && le.isAlive();
        int col = onTarget ? target.color() : color.color();
        int o = 0xC0000000;
        int h = t / 2; // centre the thickness on the pixel
        switch (shape.get()) {
            case "Dot" -> rect(g, cx - h - 1, cy - h - 1, cx - h + t + 1, cy - h + t + 1, col, o);
            case "Circle" -> {
                int r = gp + len / 2 + 1;
                for (int a = 0; a < 360; a += 6) {
                    int x = cx + Math.round(Mth.cos(a * Mth.DEG_TO_RAD) * r), y = cy + Math.round(Mth.sin(a * Mth.DEG_TO_RAD) * r);
                    rect(g, x - h, y - h, x - h + t, y - h + t, col, o);
                }
            }
            default -> {
                boolean top = !shape.is("T");
                rect(g, cx - gp - len, cy - h, cx - gp, cy - h + t, col, o);        // left
                rect(g, cx + gp + 1, cy - h, cx + gp + 1 + len, cy - h + t, col, o); // right
                if (top) rect(g, cx - h, cy - gp - len, cx - h + t, cy - gp, col, o); // up
                rect(g, cx - h, cy + gp + 1, cx - h + t, cy + gp + 1 + len, col, o); // down
                if (shape.is("Plus")) rect(g, cx - h, cy - h, cx - h + t, cy - h + t, col, o);
            }
        }
        if (centerDot.get() && !shape.is("Dot")) rect(g, cx - h, cy - h, cx - h + t, cy - h + t, col, o);
        if (indicator.get()) {
            float s = p.getAttackStrengthScale(0);
            if (s < 1) {
                int w = 16, y = cy + gp + len + 4;
                g.fill(cx - w / 2, y, cx + w / 2, y + 2, 0x80000000);
                g.fill(cx - w / 2, y, cx - w / 2 + Math.round(w * s), y + 2, ColorUtil.withAlpha(col, 0xE0));
            }
        }
    }

    private void rect(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int col, int outlineCol) {
        if (outline.get()) g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, outlineCol);
        g.fill(x0, y0, x1, y1, col);
    }
}
