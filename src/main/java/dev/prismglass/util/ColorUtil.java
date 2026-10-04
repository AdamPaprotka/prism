package dev.prismglass.util;

import java.awt.Color;

public final class ColorUtil {
    private ColorUtil() {}

    public static int alpha(int c) { return (c >>> 24) & 0xFF; }
    public static int red(int c) { return (c >> 16) & 0xFF; }
    public static int green(int c) { return (c >> 8) & 0xFF; }
    public static int blue(int c) { return c & 0xFF; }

    public static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    public static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (clamp(a) << 24); }

    /** Multiplies the existing alpha by f (0..1). */
    public static int fade(int c, float f) { return withAlpha(c, Math.round(alpha(c) * f)); }

    public static int lerp(int a, int b, float t) {
        return argb(
            (int) (alpha(a) + (alpha(b) - alpha(a)) * t),
            (int) (red(a) + (red(b) - red(a)) * t),
            (int) (green(a) + (green(b) - green(a)) * t),
            (int) (blue(a) + (blue(b) - blue(a)) * t));
    }

    public static int brighten(int c, float amount) {
        return lerp(c, withAlpha(0xFFFFFF, alpha(c)), amount);
    }

    public static int rainbow(int offsetMs, int alpha) {
        float hue = ((System.currentTimeMillis() + offsetMs) % 6000L) / 6000f;
        return withAlpha(Color.HSBtoRGB(hue, 0.55f, 1f), alpha);
    }

    public static float[] rgba(int c) {
        return new float[]{red(c) / 255f, green(c) / 255f, blue(c) / 255f, alpha(c) / 255f};
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
