package dev.prismglass.gui.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.prismglass.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * "Liquid glass" for Minecraft 26.1 (deferred GUI rendering).
 *
 * <p>26.1 GUIs don't draw immediately: screens add element render states that GuiRenderer batches later.
 * Shapes here are {@link Shape} elements (quads in the GUI pipeline; triangles are emitted as degenerate
 * quads). Overlapping elements are layered in submission order by GuiRenderState, so shadow -> body ->
 * sheen -> rim stays in order.
 *
 * <p>Liquid panels use a custom pipeline whose vertex format carries each panel's parameters (size, radius,
 * refraction, tint, dispersion, blur, rim, opacity), so any number of differently styled panels batch into one
 * draw. The refracted background is a per-frame copy of the main framebuffer taken just before GuiRenderer
 * draws (world only, no GUI), bound as Sampler0.
 */
public final class Glass {
    private static final int SEGMENTS = 8;

    // ---- pipelines ------------------------------------------------------------------------------------

    /** GUI colour pipeline without culling (our fans/strips have mixed winding). */
    public static final RenderPipeline SHAPES = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/gui_shapes"))
        .withCull(false)
        .build();

    /** Additive variant (glows, logo edges). */
    public static final RenderPipeline SHAPES_ADDITIVE = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/gui_shapes_additive"))
        .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
        .withCull(false)
        .build();

    /** Per-vertex panel parameters for the liquid shader. */
    public static final VertexFormat LIQUID_FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION)
        .add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0)
        .add("UV1", VertexFormatElement.UV1)
        .add("UV2", VertexFormatElement.UV2)
        .add("Normal", VertexFormatElement.NORMAL)
        .padding(1)
        .build();

    public static final RenderPipeline LIQUID = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/liquid_glass"))
        .withVertexShader(Identifier.fromNamespaceAndPath("prismglass", "core/liquid_glass"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("prismglass", "core/liquid_glass"))
        .withSampler("Sampler0")
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .withVertexFormat(LIQUID_FORMAT, VertexFormat.Mode.QUADS)
        .build();

    // ---- background snapshot ----------------------------------------------------------------------------

    private static @Nullable GpuTexture snapshot;
    private static @Nullable GpuTextureView snapshotView;
    /** Replaced snapshots, closed a few frames later (elements still in flight may reference them). */
    private static final List<Object[]> retired = new ArrayList<>();
    private static long snapshotCalls;
    private static boolean shaderBroken;

    /**
     * Blocky (pixel-art) mode: size of one block in GUI units, 0 = off. The ClickGUI sets it around its own
     * drawing only. Every shape is then built from whole grid cells (stepped corners, banded gradients, bevel
     * rims, stepped shadows) and the liquid shader samples once per cell; text is drawn normally, so it stays sharp.
     */
    public static float pixel;

    private Glass() {}

    /** Called right before GuiRenderer draws: copy the world image the glass refracts. */
    public static void copySnapshot() {
        if (shaderBroken) return;
        try {
            snapshotCalls++;
            retired.removeIf(r -> {
                if (snapshotCalls - (long) r[1] < 6) return false;
                try { ((AutoCloseable) r[0]).close(); } catch (Exception ignored) {}
                return true;
            });
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            GpuTexture color = main.getColorTexture();
            if (color == null) return;
            int w = color.getWidth(0), h = color.getHeight(0);
            if (snapshot == null || snapshot.getWidth(0) != w || snapshot.getHeight(0) != h) {
                // keep the old one alive a frame: elements extracted this frame may still reference it
                if (snapshotView != null) retired.add(new Object[]{snapshotView, snapshotCalls});
                if (snapshot != null) retired.add(new Object[]{snapshot, snapshotCalls});
                snapshot = RenderSystem.getDevice().createTexture(() -> "prism glass snapshot",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, w, h, 1, 1);
                snapshotView = RenderSystem.getDevice().createTextureView(snapshot);
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(color, snapshot, 0, 0, 0, 0, 0, w, h);
        } catch (Throwable t) {
            shaderBroken = true;
            dev.prismglass.Prism.LOG.error("[glass] snapshot failed, falling back to frosted glass", t);
        }
    }

    /** Kept for API compatibility with the 1.21.4 code; the snapshot is taken by the GuiRenderer hook. */
    public static void captureBackground(GuiGraphicsExtractor ctx) {}

    public static void invalidate() {}

    // ---- style ---------------------------------------------------------------------------------------------

    /**
     * @param liquid       use the refraction shader
     * @param refraction   lens strength in GUI units
     * @param dispersion   chromatic fringe strength at the rim
     * @param blur         blur radius in GUI units (keep it small: liquid glass is clear)
     * @param tintStrength how much {@code tint} is mixed over the refracted scene
     */
    public record Style(int tint, float opacity, float radius, boolean shadow, boolean sheen, float rimStrength,
                        boolean liquid, float refraction, float dispersion, float blur, float tintStrength) {
        public Style(int tint, float opacity, float radius, boolean shadow, boolean sheen, float rimStrength) {
            this(tint, opacity, radius, shadow, sheen, rimStrength, false, 0, 0, 0, 0);
        }

        public Style withAlpha(float a) {
            return new Style(tint, opacity * a, radius, shadow, sheen, rimStrength * a, liquid, refraction, dispersion, blur, tintStrength);
        }
    }

    // ---- public drawing API (same as the 1.21.4 version) ---------------------------------------------------

    public static void panel(GuiGraphicsExtractor ctx, float x, float y, float w, float h, Style s) {
        if (pixel > 0) {
            // snap the panel to the block grid so the shader's cells line up with every other shape
            float x1 = snap(x + w), y1 = snap(y + h);
            x = snap(x);
            y = snap(y);
            w = Math.max(pixel, x1 - x);
            h = Math.max(pixel, y1 - y);
        }
        float r = Math.min(s.radius(), Math.min(w, h) / 2f);
        if (s.liquid() && snapshotView != null && !shaderBroken) {
            if (s.shadow()) submit(ctx, SHAPES, shadow(new Shape.Builder(), x, y, w, h, r, 8f,
                ColorUtil.withAlpha(0x000000, (int) (110 * Math.min(1f, s.opacity() * 2f)))));
            ctx.guiRenderState.addGuiElement(new LiquidPanel(new Matrix3x2f(ctx.pose()), x, y, w, h, r, s,
                guiScale(), pixel, snapshotView, ctx.scissorStack.peek()));
            return;
        }
        if (s.liquid()) s = new Style(s.tint(), 0.55f * s.opacity(), s.radius(), s.shadow(), true, s.rimStrength());

        Shape.Builder b = new Shape.Builder();
        if (s.shadow()) shadow(b, x, y, w, h, r, 7f, ColorUtil.withAlpha(0x000000, (int) (90 * s.opacity() + 30)));
        submit(ctx, SHAPES, b);

        int a = (int) (255 * s.opacity());
        int top = ColorUtil.withAlpha(ColorUtil.lerp(s.tint(), 0xFFFFFFFF, 0.35f), Math.min(255, (int) (a * 1.15f)));
        int bottom = ColorUtil.withAlpha(ColorUtil.lerp(s.tint(), 0xFF000000, 0.25f), (int) (a * 0.85f));
        submit(ctx, SHAPES, fill(new Shape.Builder(), x, y, w, h, r, top, bottom));
        if (s.sheen()) submit(ctx, SHAPES, sheen(new Shape.Builder(), x, y, w, h, r, (int) (46 * s.rimStrength())));
        submit(ctx, SHAPES, rim(new Shape.Builder(), x, y, w, h, r, 1f, (int) (150 * s.rimStrength()), (int) (28 * s.rimStrength())));
    }

    public static void pill(GuiGraphicsExtractor ctx, float x, float y, float w, float h, int top, int bottom, float rimStrength) {
        float r = Math.min(w, h) / 2f;
        submit(ctx, SHAPES, fill(new Shape.Builder(), x, y, w, h, r, top, bottom));
        submit(ctx, SHAPES, rim(new Shape.Builder(), x, y, w, h, r, 0.8f, (int) (140 * rimStrength), (int) (20 * rimStrength)));
    }

    public static void rounded(GuiGraphicsExtractor ctx, float x, float y, float w, float h, float r, int top, int bottom) {
        submit(ctx, SHAPES, fill(new Shape.Builder(), x, y, w, h, Math.min(r, Math.min(w, h) / 2f), top, bottom));
    }

    public static void glow(GuiGraphicsExtractor ctx, float x, float y, float w, float h, float r, float size, int color) {
        submit(ctx, SHAPES, shadow(new Shape.Builder(), x, y, w, h, Math.min(r, Math.min(w, h) / 2f), size, color));
    }

    public static void submit(GuiGraphicsExtractor ctx, RenderPipeline pipeline, Shape.Builder b) {
        if (b.isEmpty()) return;
        if (pixel > 0 && !b.cells) b = pixelate(b);
        ctx.guiRenderState.addGuiElement(b.build(pipeline, new Matrix3x2f(ctx.pose()), ctx.scissorStack.peek()));
    }

    private static float guiScale() { return (float) Minecraft.getInstance().getWindow().getGuiScale(); }

    // ---- geometry -------------------------------------------------------------------------------------

    /** Outline of a rounded rect: [px, py, nx, ny] per point, clockwise from the top-left arc. */
    static float[] path(float x, float y, float w, float h, float r) {
        float[] out = new float[4 * 4 * (SEGMENTS + 1)];
        float[][] centers = {{x + r, y + r}, {x + w - r, y + r}, {x + w - r, y + h - r}, {x + r, y + h - r}};
        float[] startAngles = {180f, 270f, 0f, 90f};
        int i = 0;
        for (int c = 0; c < 4; c++) {
            for (int s = 0; s <= SEGMENTS; s++) {
                double ang = Math.toRadians(startAngles[c] + 90.0 * s / SEGMENTS);
                float nx = (float) Math.cos(ang), ny = (float) Math.sin(ang);
                out[i++] = centers[c][0] + nx * r;
                out[i++] = centers[c][1] + ny * r;
                out[i++] = nx;
                out[i++] = ny;
            }
        }
        return out;
    }

    static Shape.Builder fill(Shape.Builder b, float x, float y, float w, float h, float r, int top, int bottom) {
        if (pixel > 0) return blockyFill(b, x, y, w, h, r, top, bottom);
        float[] p = path(x, y, w, h, r);
        float cx = x + w / 2f, cy = y + h / 2f;
        int cc = ColorUtil.lerp(top, bottom, 0.5f);
        for (int i = 0; i < p.length; i += 4) {
            int j = (i + 4) % p.length;
            b.tri(cx, cy, cc,
                p[i], p[i + 1], ColorUtil.lerp(top, bottom, (p[i + 1] - y) / h),
                p[j], p[j + 1], ColorUtil.lerp(top, bottom, (p[j + 1] - y) / h));
        }
        return b;
    }

    /** Edge ring whose brightness follows the edge normal against a top-left light. */
    static Shape.Builder rim(Shape.Builder b, float x, float y, float w, float h, float r, float t, int lit, int dim) {
        if (pixel > 0) return blockyRim(b, x, y, w, h, r, ColorUtil.withAlpha(0xFFFFFF, lit), ColorUtil.withAlpha(0x000000, Math.min(255, lit / 2 + dim)));
        float[] p = path(x, y, w, h, r);
        for (int i = 0; i < p.length; i += 4) {
            int j = (i + 4) % p.length;
            int ca = rimColor(p[i + 2], p[i + 3], lit, dim), cb = rimColor(p[j + 2], p[j + 3], lit, dim);
            b.quad(p[i], p[i + 1], ca,
                p[j], p[j + 1], cb,
                p[j] - p[j + 2] * t, p[j + 1] - p[j + 3] * t, ColorUtil.withAlpha(cb, ColorUtil.alpha(cb) / 3),
                p[i] - p[i + 2] * t, p[i + 1] - p[i + 3] * t, ColorUtil.withAlpha(ca, ColorUtil.alpha(ca) / 3));
        }
        return b;
    }

    private static int rimColor(float nx, float ny, int lit, int dim) {
        float d = Math.max(0f, -0.6f * nx - 0.8f * ny);
        float d2 = Math.max(0f, 0.6f * nx + 0.8f * ny) * 0.35f;
        int alpha = (int) (dim + (lit - dim) * Math.max(d * d, d2));
        return ColorUtil.withAlpha(0xFFFFFF, Math.min(255, alpha));
    }

    static Shape.Builder shadow(Shape.Builder b, float x, float y, float w, float h, float r, float size, int color) {
        if (pixel > 0) return blockyShadow(b, x, y, w, h, r, size, color);
        float[] p = path(x, y + size * 0.25f, w, h, r);
        int clear = ColorUtil.withAlpha(color, 0);
        for (int i = 0; i < p.length; i += 4) {
            int j = (i + 4) % p.length;
            b.quad(p[i], p[i + 1], color,
                p[i] + p[i + 2] * size, p[i + 1] + p[i + 3] * size, clear,
                p[j] + p[j + 2] * size, p[j + 1] + p[j + 3] * size, clear,
                p[j], p[j + 1], color);
        }
        return b;
    }

    static Shape.Builder sheen(Shape.Builder b, float x, float y, float w, float h, float r, int alpha) {
        if (pixel > 0) return blockySheen(b, x, y, w, h, r, alpha);
        float inset = Math.max(1.5f, r * 0.35f);
        float bandH = Math.min(h * 0.45f, 22f);
        float x0 = x + inset, x1 = x + w - inset, y0 = y + 1.5f;
        int c0 = ColorUtil.withAlpha(0xFFFFFF, alpha), c1 = ColorUtil.withAlpha(0xFFFFFF, 0);
        b.quad(x0, y0, c0, x0, y0 + bandH, c1, x1, y0 + bandH * 0.35f, c1, x1, y0, ColorUtil.withAlpha(0xFFFFFF, alpha / 3));
        return b;
    }

    // ---- blocky (pixel-art) geometry ------------------------------------------------------------------

    private static float snap(float v) { return Math.round(v / pixel) * pixel; }

    /** A plain rectangle on the block grid (at least one block each way); for the GUI's flat fills. */
    public static void rect(GuiGraphicsExtractor ctx, float x0, float y0, float x1, float y1, int color) {
        Shape.Builder b = new Shape.Builder();
        b.cells = true;
        int gx0 = Math.round(x0 / pixel), gy0 = Math.round(y0 / pixel);
        int gx1 = Math.max(gx0, Math.round(x1 / pixel) - 1), gy1 = Math.max(gy0, Math.round(y1 / pixel) - 1);
        for (int gy = gy0; gy <= gy1; gy++) cells(b, gx0, gy, gx1, color);
        submit(ctx, SHAPES, b);
    }

    /** Rows of a rounded rect on the block grid: row {@code i} is grid row {@code gy0 + i}, cells {@code l[i]..r[i]}. */
    private record Spans(int gy0, int[] l, int[] r) {}

    /** A cell is in when its centre is (so shapes keep their size); thin shapes keep at least one cell per row. */
    private static Spans spans(float x, float y, float w, float h, float r) {
        float p = pixel;
        int gy0 = Math.round(y / p), gy1 = Math.max(gy0, Math.round((y + h) / p) - 1);
        int n = gy1 - gy0 + 1;
        int[] l = new int[n], rr = new int[n];
        for (int i = 0; i < n; i++) {
            float cy = Math.min(Math.max((gy0 + i + 0.5f) * p, y), y + h);
            float dy = cy < y + r ? y + r - cy : cy > y + h - r ? cy - (y + h - r) : 0;
            float inset = dy >= r ? r : r - (float) Math.sqrt(r * r - dy * dy);
            l[i] = Math.round((x + inset) / p);
            rr[i] = Math.max(l[i], Math.round((x + w - inset) / p) - 1);
        }
        return new Spans(gy0, l, rr);
    }

    private static void cells(Shape.Builder b, int gx0, int gy, int gx1, int c) {
        float p = pixel, x0 = gx0 * p, x1 = (gx1 + 1) * p, y0 = gy * p, y1 = (gy + 1) * p;
        b.quad(x0, y0, c, x0, y1, c, x1, y1, c, x1, y0, c);
    }

    /** Pixel-art colour: 16 levels per channel, so gradients band like hand-made sprites. */
    private static int quantize(int c) {
        int a = (c >>> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
        a = (a + 8) / 17 * 17; r = (r + 8) / 17 * 17; g = (g + 8) / 17 * 17; bl = (bl + 8) / 17 * 17;
        return a << 24 | r << 16 | g << 8 | bl;
    }

    private static Shape.Builder blockyFill(Shape.Builder b, float x, float y, float w, float h, float r, int top, int bottom) {
        b.cells = true;
        Spans s = spans(x, y, w, h, r);
        int n = s.l().length;
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0.5f : Math.round(4f * i / (n - 1)) / 4f; // five bands top -> bottom
            cells(b, s.l()[i], s.gy0() + i, s.r()[i], quantize(ColorUtil.lerp(top, bottom, t)));
        }
        return b;
    }

    /** One-block bevel: edges facing up/left catch the light, edges facing down/right are shaded. */
    private static Shape.Builder blockyRim(Shape.Builder b, float x, float y, float w, float h, float r, int lit, int shade) {
        b.cells = true;
        Spans s = spans(x, y, w, h, r);
        int n = s.l().length;
        for (int i = 0; i < n; i++) {
            int l = s.l()[i], rr = s.r()[i], gy = s.gy0() + i;
            int al = i > 0 ? s.l()[i - 1] : Integer.MAX_VALUE, ar = i > 0 ? s.r()[i - 1] : Integer.MIN_VALUE;
            int bl = i < n - 1 ? s.l()[i + 1] : Integer.MAX_VALUE, br = i < n - 1 ? s.r()[i + 1] : Integer.MIN_VALUE;
            for (int gx = l; gx <= rr; ) {
                int kind = rimKind(gx, l, rr, al, ar, bl, br), end = gx;
                while (end < rr && rimKind(end + 1, l, rr, al, ar, bl, br) == kind) end++;
                if (kind != 0) cells(b, gx, gy, end, kind == 1 ? lit : shade);
                gx = end + 1;
            }
        }
        return b;
    }

    private static int rimKind(int gx, int l, int r, int al, int ar, int bl, int br) {
        if (gx < al || gx > ar || gx == l) return 1;
        if (gx < bl || gx > br || gx == r) return 2;
        return 0;
    }

    /** Three stepped rings fading out, offset down like the smooth shadow. */
    private static Shape.Builder blockyShadow(Shape.Builder b, float x, float y, float w, float h, float r, float size, int color) {
        b.cells = true;
        y += size * 0.25f;
        int bands = 3;
        Spans inner = spans(x, y, w, h, r);
        for (int k = 1; k <= bands; k++) {
            float e = size * k / bands;
            Spans outer = spans(x - e, y - e, w + 2 * e, h + 2 * e, r + e);
            int c = quantize(ColorUtil.withAlpha(color, Math.round(ColorUtil.alpha(color) * (1f - (k - 0.5f) / bands))));
            for (int i = 0; i < outer.l().length; i++) {
                int gy = outer.gy0() + i, j = gy - inner.gy0(), l = outer.l()[i], rr = outer.r()[i];
                if (j < 0 || j >= inner.l().length) { cells(b, l, gy, rr, c); continue; }
                if (inner.l()[j] > l) cells(b, l, gy, Math.min(rr, inner.l()[j] - 1), c);
                if (inner.r()[j] < rr) cells(b, Math.max(l, inner.r()[j] + 1), gy, rr, c);
            }
            inner = outer;
        }
        return b;
    }

    /** Glare as stepped strips across the top, fading down. */
    private static Shape.Builder blockySheen(Shape.Builder b, float x, float y, float w, float h, float r, int alpha) {
        b.cells = true;
        float inset = Math.max(pixel, r * 0.35f), bandH = Math.min(h * 0.45f, 22f);
        int strips = Math.max(1, Math.round(bandH * 0.6f / pixel));
        int gy0 = Math.round((y + pixel) / pixel), gx0 = Math.round((x + inset) / pixel);
        int gx1 = Math.max(gx0, Math.round((x + w - inset) / pixel) - 1);
        for (int k = 0; k < strips; k++) {
            int c = quantize(ColorUtil.withAlpha(0xFFFFFF, Math.round(alpha * (1f - k / (float) strips))));
            int cut = Math.round((gx1 - gx0) * 0.25f * k / strips); // lower strips end a bit earlier on the right
            cells(b, gx0, gy0 + k, gx1 - cut, c);
        }
        return b;
    }

    /**
     * Any other shape (logo, glows): rasterised onto the block grid quad by quad. A cell takes the colour at its
     * centre; quads thinner than a block (lines) cover every cell they touch, so lines become pixel staircases.
     */
    private static Shape.Builder pixelate(Shape.Builder src) {
        Shape.Builder out = new Shape.Builder();
        out.cells = true;
        float p = pixel, q = p * 0.3f;
        float[] xy = src.xy;
        int[] col = src.colors;
        float[][] sub = {{-q, -q}, {q, -q}, {-q, q}, {q, q}};
        float[] w = new float[3];
        for (int v = 0; v + 3 < src.count; v += 4) {
            boolean tri = xy[(v + 3) * 2] == xy[(v + 2) * 2] && xy[(v + 3) * 2 + 1] == xy[(v + 2) * 2 + 1];
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, longest = 0;
            for (int k = 0; k < 4; k++) {
                float px = xy[(v + k) * 2], py = xy[(v + k) * 2 + 1];
                minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                int nk = v + (k + 1) % 4;
                longest = Math.max(longest, (float) Math.hypot(xy[nk * 2] - px, xy[nk * 2 + 1] - py));
            }
            float area = Math.abs(cross(xy, v, v + 1, v + 2)) + (tri ? 0 : Math.abs(cross(xy, v, v + 2, v + 3)));
            if (area < 1e-6f) continue;
            boolean thin = (tri ? area : area / 2f) / Math.max(longest, 1e-4f) < p;
            int gx0 = (int) Math.floor(minX / p), gx1 = (int) Math.floor(maxX / p);
            int gy0 = (int) Math.floor(minY / p), gy1 = (int) Math.floor(maxY / p);
            for (int gy = gy0; gy <= gy1; gy++) {
                int runStart = 0, runColor = 0;
                boolean inRun = false;
                for (int gx = gx0; gx <= gx1 + 1; gx++) {
                    int c = 0;
                    boolean hit = false;
                    if (gx <= gx1) {
                        float cx = (gx + 0.5f) * p, cy = (gy + 0.5f) * p;
                        int b = v + 1, c2 = v + 2;
                        hit = bary(xy, v, b, c2, cx, cy, w, false);
                        if (!hit && !tri) { b = v + 2; c2 = v + 3; hit = bary(xy, v, b, c2, cx, cy, w, false); }
                        if (!hit && thin) {
                            for (float[] o : sub) {
                                if (bary(xy, v, v + 1, v + 2, cx + o[0], cy + o[1], w, false)
                                    || !tri && bary(xy, v, v + 2, v + 3, cx + o[0], cy + o[1], w, false)) { hit = true; break; }
                            }
                            if (hit) { b = v + 1; c2 = v + 2; bary(xy, v, b, c2, cx, cy, w, true); }
                        }
                        if (hit) c = quantize(mix(col[v], col[b], col[c2], w));
                    }
                    if (inRun && (!hit || c != runColor)) { cells(out, runStart, gy, gx - 1, runColor); inRun = false; }
                    if (hit && !inRun) { inRun = true; runStart = gx; runColor = c; }
                }
            }
        }
        return out;
    }

    private static float cross(float[] xy, int a, int b, int c) {
        return (xy[b * 2] - xy[a * 2]) * (xy[c * 2 + 1] - xy[a * 2 + 1]) - (xy[c * 2] - xy[a * 2]) * (xy[b * 2 + 1] - xy[a * 2 + 1]);
    }

    /** Barycentric weights of (px, py) in triangle a-b-c into {@code w}; true when inside. {@code clamp}: always succeeds, clamped. */
    private static boolean bary(float[] xy, int a, int b, int c, float px, float py, float[] w, boolean clamp) {
        float ax = xy[a * 2], ay = xy[a * 2 + 1], bx = xy[b * 2], by = xy[b * 2 + 1], cx = xy[c * 2], cy = xy[c * 2 + 1];
        float d = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if (Math.abs(d) < 1e-9f) return false;
        float w1 = ((px - ax) * (cy - ay) - (cx - ax) * (py - ay)) / d;
        float w2 = ((bx - ax) * (py - ay) - (px - ax) * (by - ay)) / d;
        float w0 = 1 - w1 - w2;
        if (clamp) {
            w0 = Math.max(0, w0); w1 = Math.max(0, w1); w2 = Math.max(0, w2);
            float sum = Math.max(1e-6f, w0 + w1 + w2);
            w[0] = w0 / sum; w[1] = w1 / sum; w[2] = w2 / sum;
            return true;
        }
        if (w0 < 0 || w1 < 0 || w2 < 0) return false;
        w[0] = w0; w[1] = w1; w[2] = w2;
        return true;
    }

    private static int mix(int c0, int c1, int c2, float[] w) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            float v = ((c0 >>> shift) & 0xFF) * w[0] + ((c1 >>> shift) & 0xFF) * w[1] + ((c2 >>> shift) & 0xFF) * w[2];
            out |= Math.min(255, Math.max(0, Math.round(v))) << shift;
        }
        return out;
    }


    // ---- element types -------------------------------------------------------------------------------------

    /** A batch of coloured quads (triangles as degenerate quads) in one GUI element. */
    public record Shape(RenderPipeline pipeline, Matrix3x2fc pose, float[] xy, int[] colors, int count,
                        @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer vc) {
            for (int i = 0; i < count; i++) vc.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setColor(colors[i]);
        }

        @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }

        public static final class Builder {
            private float[] xy = new float[256];
            private int[] colors = new int[128];
            private int count;
            /** Already made of grid cells (Blocky mode): submit it as is. */
            boolean cells;
            private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

            public boolean isEmpty() { return count == 0; }

            public Builder vertex(float x, float y, int color) {
                if (count == colors.length) {
                    colors = java.util.Arrays.copyOf(colors, count * 2);
                    xy = java.util.Arrays.copyOf(xy, count * 4);
                }
                xy[count * 2] = x;
                xy[count * 2 + 1] = y;
                colors[count++] = color;
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                return this;
            }

            public Builder quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
                return vertex(x0, y0, c0).vertex(x1, y1, c1).vertex(x2, y2, c2).vertex(x3, y3, c3);
            }

            public Builder tri(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2) {
                return quad(x0, y0, c0, x1, y1, c1, x2, y2, c2, x2, y2, c2);
            }

            public Shape build(RenderPipeline pipeline, Matrix3x2fc pose, @Nullable ScreenRectangle scissor) {
                int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
                ScreenRectangle b = new ScreenRectangle(x0, y0, (int) Math.ceil(maxX) - x0 + 1, (int) Math.ceil(maxY) - y0 + 1)
                    .transformMaxBounds(pose);
                if (scissor != null) b = scissor.intersection(b);
                return new Shape(pipeline, pose, java.util.Arrays.copyOf(xy, count * 2), java.util.Arrays.copyOf(colors, count), count, scissor, b);
            }
        }
    }

    /** One liquid glass panel: a quad whose vertices carry all of the panel's shader parameters. */
    record LiquidPanel(Matrix3x2fc pose, float x, float y, float w, float h, float radius, Style s, float scale,
                       float pixel, GpuTextureView background, @Nullable ScreenRectangle scissorArea) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer vc) {
            int wPx = Math.round(w * scale), hPx = Math.round(h * scale);
            int r4 = Math.min(255, Math.round(radius * scale * 4));
            int tint100 = Math.round(Math.max(0, Math.min(1, s.tintStrength())) * 100);
            int uv2x = r4 + 256 * tint100;
            // y: refraction*4 in the low 10 bits, Blocky cell size (pixels*2) above them
            int uv2y = Math.min(1023, Math.round(s.refraction() * scale * 4)) + 1024 * Math.min(31, Math.round(pixel * scale * 2));
            int color = ColorUtil.withAlpha(s.tint(), Math.round(255 * Math.min(1f, s.opacity())));
            float nd = Math.min(1f, s.dispersion() / 4f), nb = Math.min(1f, s.blur() * scale / 32f), nr = Math.min(1f, s.rimStrength() / 2f);
            float[][] corners = {{x - 1, y - 1}, {x - 1, y + h + 1}, {x + w + 1, y + h + 1}, {x + w + 1, y - 1}};
            for (float[] c : corners) {
                vc.addVertexWith2DPose(pose, c[0], c[1]).setColor(color)
                    .setUv((c[0] - x) * scale, (c[1] - y) * scale)
                    .setUv1(wPx, hPx)
                    .setUv2(uv2x, uv2y)
                    .setNormal(nd, nb, nr);
            }
        }

        @Override public RenderPipeline pipeline() { return LIQUID; }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.singleTexture(background, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }

        @Override
        public @Nullable ScreenRectangle bounds() {
            ScreenRectangle b = new ScreenRectangle((int) Math.floor(x) - 1, (int) Math.floor(y) - 1, (int) Math.ceil(w) + 3, (int) Math.ceil(h) + 3)
                .transformMaxBounds(pose);
            return scissorArea != null ? scissorArea.intersection(b) : b;
        }
    }
}
