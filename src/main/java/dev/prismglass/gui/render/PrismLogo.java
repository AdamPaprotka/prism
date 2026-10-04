package dev.prismglass.gui.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The Prism logo (26.1): two nested glass pyramids with rainbow glowing edges and a bright light at the
 * core. The outer pyramid spins one way, the inner one the other; the light pulses and shines through both
 * layers of glass. Faces are depth-sorted and submitted back to front as GUI shape elements (overlapping
 * submissions stay in order); light and edges use the additive pipeline.
 */
public final class PrismLogo {
    private static final float[][] VERTS = {
        {0f, -0.66f, 0f},
        {-0.5f, 0.34f, -0.5f}, {0.5f, 0.34f, -0.5f}, {0.5f, 0.34f, 0.5f}, {-0.5f, 0.34f, 0.5f}
    };
    private static final int[][] EDGES = {{0, 1}, {0, 2}, {0, 3}, {0, 4}, {1, 2}, {2, 3}, {3, 4}, {4, 1}};
    private static final int[][] FACES = {{0, 1, 2}, {0, 2, 3}, {0, 3, 4}, {0, 4, 1}, {1, 2, 3}, {1, 3, 4}};
    private static final float FACE_ALPHA_TOP = 0.40f, FACE_ALPHA_BASE = 0.12f, INNER_SCALE = 0.48f, INNER_SPIN = -1.0f;

    private PrismLogo() {}

    private record Face(float[][] pts, float depth, int index) {}

    public static void draw(GuiGraphicsExtractor ctx, float cx, float cy, float size, float speed, float alpha) {
        double t = System.nanoTime() / 1e9;
        float yaw = (float) (t * speed * Math.PI * 2);
        float[][] outer = project(cx, cy, size, 1f, yaw);
        float[][] inner = project(cx, cy, size, INNER_SCALE, yaw * INNER_SPIN + 0.4f);
        float[] core = projectPoint(cx, cy, size, 0f, 0.02f, 0f);

        List<Face> faces = new ArrayList<>(12);
        for (int i = 0; i < FACES.length; i++) {
            faces.add(face(outer, FACES[i], i));
            faces.add(face(inner, FACES[i], i));
        }
        faces.sort((a, b) -> Float.compare(b.depth(), a.depth())); // far first

        float pulse = 0.85f + 0.15f * (float) Math.sin(t * 2.6);
        boolean lightDrawn = false;
        Glass.Shape.Builder glass = new Glass.Shape.Builder();
        for (Face f : faces) {
            if (!lightDrawn && f.depth() < core[2]) {
                // flush the faces behind the light, then the light, then continue in front of it
                Glass.submit(ctx, Glass.SHAPES, glass);
                glass = new Glass.Shape.Builder();
                Glass.submit(ctx, Glass.SHAPES_ADDITIVE, light(new Glass.Shape.Builder(), core, size, alpha * pulse));
                lightDrawn = true;
            }
            glassFace(glass, f, alpha);
        }
        Glass.submit(ctx, Glass.SHAPES, glass);
        Glass.Shape.Builder add = new Glass.Shape.Builder();
        if (!lightDrawn) light(add, core, size, alpha * pulse);
        bloom(add, core, size, alpha * pulse);

        float hue = (float) ((t * 0.25) % 1.0);
        float unit = Math.max(0.6f, size / 28f);
        edges(add, outer, hue, unit * 3.2f, alpha * 0.10f);
        edges(add, outer, hue, unit * 1.9f, alpha * 0.22f);
        edges(add, outer, hue, unit * 0.9f, alpha * 0.95f);
        edges(add, inner, hue + 0.5f, unit * 2.2f, alpha * 0.12f);
        edges(add, inner, hue + 0.5f, unit * 0.7f, alpha * 0.85f);
        Glass.submit(ctx, Glass.SHAPES_ADDITIVE, add);
    }

    private static float[][] project(float cx, float cy, float size, float scale, float yaw) {
        float[][] p = new float[VERTS.length][];
        for (int i = 0; i < VERTS.length; i++) {
            float x = VERTS[i][0] * scale, y = VERTS[i][1] * scale, z = VERTS[i][2] * scale;
            float rx = x * (float) Math.cos(yaw) - z * (float) Math.sin(yaw);
            float rz = x * (float) Math.sin(yaw) + z * (float) Math.cos(yaw);
            p[i] = projectPoint(cx, cy, size, rx, y, rz);
        }
        return p;
    }

    private static float[] projectPoint(float cx, float cy, float size, float x, float y, float z) {
        float tilt = 0.42f;
        float ry = y * (float) Math.cos(tilt) - z * (float) Math.sin(tilt);
        float rz = y * (float) Math.sin(tilt) + z * (float) Math.cos(tilt);
        float persp = 2.6f / (2.6f + rz);
        return new float[]{cx + x * size * persp, cy + ry * size * persp, rz};
    }

    private static Face face(float[][] p, int[] idx, int index) {
        float[][] pts = {p[idx[0]], p[idx[1]], p[idx[2]]};
        return new Face(pts, (pts[0][2] + pts[1][2] + pts[2][2]) / 3f, index);
    }

    private static void glassFace(Glass.Shape.Builder b, Face f, float alpha) {
        boolean base = f.index() >= 4;
        float facing = 0.85f + 0.15f * (f.index() % 4) / 3f;
        int top = argb(alpha * FACE_ALPHA_TOP * facing, 0.59f, 0.75f, 1f);
        int bottom = argb(alpha * FACE_ALPHA_BASE * facing, 0.52f, 0.68f, 1f);
        b.tri(f.pts()[0][0], f.pts()[0][1], base ? bottom : top,
            f.pts()[1][0], f.pts()[1][1], bottom,
            f.pts()[2][0], f.pts()[2][1], bottom);
    }

    private static Glass.Shape.Builder light(Glass.Shape.Builder b, float[] c, float size, float alpha) {
        radial(b, c[0], c[1], size * 0.60f, argb(alpha * 0.55f, 1f, 0.84f, 0.59f));
        radial(b, c[0], c[1], size * 0.30f, argb(alpha, 1f, 0.93f, 0.77f));
        radial(b, c[0], c[1], size * 0.12f, argb(1f, 1f, 1f, 1f));
        radial(b, c[0], c[1], size * 0.05f, argb(1f, 1f, 1f, 1f));
        return b;
    }

    private static void bloom(Glass.Shape.Builder b, float[] c, float size, float alpha) {
        radial(b, c[0], c[1], size * 0.34f, argb(alpha * 0.55f, 1f, 0.89f, 0.71f));
        radial(b, c[0], c[1], size * 0.10f, argb(1f, 1f, 1f, 1f));
        radial(b, c[0], c[1], size * 0.045f, argb(1f, 1f, 1f, 1f));
    }

    private static void radial(Glass.Shape.Builder b, float cx, float cy, float r, int center) {
        int edge = center & 0x00FFFFFF;
        int seg = 24;
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            b.tri(cx, cy, center,
                cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r, edge,
                cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, edge);
        }
    }

    private static void edges(Glass.Shape.Builder b, float[][] p, float hueBase, float width, float alpha) {
        for (int e = 0; e < EDGES.length; e++) {
            float[] a = p[EDGES[e][0]], c = p[EDGES[e][1]];
            float depthA = 0.55f + 0.45f * clamp01(0.5f - a[2]);
            float depthC = 0.55f + 0.45f * clamp01(0.5f - c[2]);
            int colA = hsv(hueBase + e * 0.125f, alpha * depthA);
            int colC = hsv(hueBase + e * 0.125f + 0.18f, alpha * depthC);
            float dx = c[0] - a[0], dy = c[1] - a[1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-3f) continue;
            float nx = -dy / len * width / 2f, ny = dx / len * width / 2f;
            b.quad(a[0] + nx, a[1] + ny, colA, a[0] - nx, a[1] - ny, colA, c[0] - nx, c[1] - ny, colC, c[0] + nx, c[1] + ny, colC);
        }
    }

    private static int hsv(float hue, float alpha) {
        int rgb = Color.HSBtoRGB(hue - (float) Math.floor(hue), 0.75f, 1f);
        return ((int) (clamp01(alpha) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int argb(float a, float r, float g, float b) {
        return ((int) (clamp01(a) * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
