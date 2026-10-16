package dev.prismglass.gui.render;

import dev.prismglass.Prism;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The "China hat": a flat translucent cone floating just over a player's head, drawn in the world pass.
 * Settings live in the ClickGUI's Config menu (PlayerHat, HatOthers, HatPrismColor/HatColor, HatOpacity, HatSize,
 * HatSpin). By default it takes Prism's accent colour.
 * Only your own client draws it - nobody else sees it.
 */
public final class PlayerHat {
    private static final int SEGMENTS = 36;

    private PlayerHat() {}

    public static void render(float delta) {
        Minecraft mc = Minecraft.getInstance();
        ClickGui gui = Prism.modules().get(ClickGui.class);
        if (gui == null || !gui.playerHat.get()) return;
        for (Player p : mc.level.players()) {
            if (p.isInvisible()) continue;
            if (p == mc.player) {
                if (mc.options.getCameraType().isFirstPerson()) continue; // it'd sit right over the camera
            } else if (!gui.hatOthers.get()) continue;
            draw(p, delta, gui);
        }
    }

    private static void draw(Player p, float delta, ClickGui gui) {
        Vec3 pos = p.getPosition(delta);
        double top = pos.y + p.getBbHeight() + 0.03;        // just above the head (lower when sneaking)
        double radius = 0.62 * gui.hatSize.get(), rise = 0.32 * gui.hatSize.get();
        Vec3 apex = new Vec3(pos.x, top + rise, pos.z);
        int alpha = (int) (gui.hatAlpha.get() * 255);
        double spin = gui.hatSpin.get() ? (System.currentTimeMillis() % 6000) / 6000.0 * Math.PI * 2 : 0;
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2 * i / SEGMENTS, a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
            Vec3 e0 = new Vec3(pos.x + Math.cos(a0) * radius, top, pos.z + Math.sin(a0) * radius);
            Vec3 e1 = new Vec3(pos.x + Math.cos(a1) * radius, top, pos.z + Math.sin(a1) * radius);
            // light from one side so the cone reads as 3D; with HatSpin the light goes round
            float shade = (float) (0.72 + 0.28 * Math.cos(a0 + spin));
            // accent colours can be animated/gradient: offset by segment so a rainbow runs once round the brim (6 s hue cycle)
            int base = gui.hatAccent.get() ? gui.accent.color(i * 6000 / SEGMENTS) : gui.hatColor.color();
            int c = ColorUtil.withAlpha(scale(base, shade), alpha);
            Render3D.quad(apex, e0, e1, e1, c, false);  // top side
            Render3D.quad(apex, e1, e0, e0, c, false);  // underside (both windings: no back-face holes)
            Render3D.line(e0, e1, ColorUtil.withAlpha(scale(base, 1.15f), Math.min(255, alpha + 80))); // rim
        }
    }

    private static int scale(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
