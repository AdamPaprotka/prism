package dev.prismglass.gui.render;

import dev.prismglass.Prism;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Prism's developer mark: a small spinning glass prism over the dev's head (veritytunghacker, by account UUID or the
 * offline-mode UUID of that name). Drawn by Prism clients only, in the world pass like PlayerHat: no packets, no
 * assets, no keys or translations, so it adds nothing a server can see or probe.
 */
public final class DevPrism {
    private static final Set<UUID> DEVS = Set.of(
        UUID.fromString("a371b7df-7636-4c23-9890-eae0046a50dc"),
        UUID.nameUUIDFromBytes("OfflinePlayer:veritytunghacker".getBytes(StandardCharsets.UTF_8)));
    /** Dev test (-Pcameratest): on everyone, so the test player shows it. */
    private static final boolean TEST = Boolean.getBoolean("prismglass.cameratest");
    private static final double SIDE = 0.5, HEIGHT = SIDE * Math.sqrt(3) / 2, DEPTH = 0.18;

    private DevPrism() {}

    public static void render(float delta) {
        Minecraft mc = Minecraft.getInstance();
        for (Player p : mc.level.players()) {
            if (!(DEVS.contains(p.getUUID()) || TEST) || p.isInvisible()) continue;
            if (p == mc.player && mc.options.getCameraType().isFirstPerson()) continue; // it'd sit right over the camera
            draw(p, delta, hatOn(p, mc));
        }
    }

    /** Whether PlayerHat draws its hat on this player (the prism floats above it then). */
    private static boolean hatOn(Player p, Minecraft mc) {
        ClickGui gui = Prism.modules().get(ClickGui.class);
        return gui != null && gui.playerHat.get() && (p == mc.player || gui.hatOthers.get());
    }

    private static void draw(Player p, float delta, boolean hat) {
        Vec3 pos = p.getPosition(delta);
        long now = System.currentTimeMillis();
        double spin = (now % 5000) / 5000.0 * Math.PI * 2;
        double bob = Math.sin((now % 3000) / 3000.0 * Math.PI * 2) * 0.05;
        double base = pos.y + p.getBbHeight() + (hat ? 0.62 : 0.32) + bob;
        // triangle in the local XY plane (point up), extruded along local Z, turned around the Y axis
        double[][] tri = {{0, HEIGHT}, {-SIDE / 2, 0}, {SIDE / 2, 0}};
        Vec3[] front = new Vec3[3], back = new Vec3[3];
        for (int i = 0; i < 3; i++) {
            front[i] = at(pos, base, tri[i][0], tri[i][1], DEPTH / 2, spin);
            back[i] = at(pos, base, tri[i][0], tri[i][1], -DEPTH / 2, spin);
        }
        // glass faces, light splitting into a rainbow on the sides (hues drift round over 4 s)
        int glass = 0x70E6F4FF;
        tri(front[0], front[1], front[2], glass);
        tri(back[0], back[2], back[1], glass);
        float hue = (now % 4000) / 4000f;
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            int c = ColorUtil.withAlpha(hsv((hue + i / 3f) % 1f, 0.75f, 1f), 150);
            Render3D.quad(front[i], front[j], back[j], back[i], c, false);
            Render3D.quad(front[i], back[i], back[j], front[j], c, false); // both windings: no back-face holes
        }
        int edge = 0xF0FFFFFF;
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            Render3D.line(front[i], front[j], edge);
            Render3D.line(back[i], back[j], edge);
            Render3D.line(front[i], back[i], edge);
        }
    }

    private static void tri(Vec3 a, Vec3 b, Vec3 c, int color) {
        Render3D.quad(a, b, c, c, color, false);
        Render3D.quad(a, c, b, b, color, false);
    }

    private static Vec3 at(Vec3 feet, double baseY, double x, double y, double z, double spin) {
        double cos = Math.cos(spin), sin = Math.sin(spin);
        return new Vec3(feet.x + x * cos - z * sin, baseY + y, feet.z + x * sin + z * cos);
    }

    private static int hsv(float h, float s, float v) {
        float r, g, b;
        int i = (int) (h * 6);
        float f = h * 6 - i, p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
