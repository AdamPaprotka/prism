package dev.prismglass.util;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import dev.prismglass.streamproof.Overlay;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * World-space rendering for 26.1. Modules push shapes during onRender3D; they go into four render types
 * (filled/lines x see-through/depth-tested) of the frame's buffer source and are flushed at the end, so
 * hundreds of ESP boxes cost a handful of draws.
 *
 * <p>Coordinates are absolute world coordinates; the camera offset is applied here (26.1 level rendering is
 * camera-relative, with the view rotation in the model-view transform).
 */
public final class Render3D {
    private static final Minecraft mc = Minecraft.getInstance();

    private static RenderPipeline pipeline(String name, RenderPipeline.Snippet snippet, boolean xray) {
        return RenderPipeline.builder(snippet)
            .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/" + name))
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(xray ? CompareOp.ALWAYS_PASS : CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();
    }

    private static final RenderType FILL_XRAY = RenderType.create("prism_fill_xray",
        RenderSetup.builder(pipeline("esp_fill_xray", RenderPipelines.DEBUG_FILLED_SNIPPET, true)).setOutputTarget(Overlay.OUTPUT).createRenderSetup());
    private static final RenderType FILL_DEPTH = RenderType.create("prism_fill_depth",
        RenderSetup.builder(pipeline("esp_fill_depth", RenderPipelines.DEBUG_FILLED_SNIPPET, false)).setOutputTarget(Overlay.OUTPUT).createRenderSetup());
    private static final RenderType LINES_XRAY = RenderType.create("prism_lines_xray",
        RenderSetup.builder(pipeline("esp_lines_xray", RenderPipelines.LINES_SNIPPET, true)).setOutputTarget(Overlay.OUTPUT).createRenderSetup());
    private static final RenderType LINES_DEPTH = RenderType.create("prism_lines_depth",
        RenderSetup.builder(pipeline("esp_lines_depth", RenderPipelines.LINES_SNIPPET, false)).setOutputTarget(Overlay.OUTPUT).createRenderSetup());

    /**
     * Own buffer source with a fixed buffer per type: the level's source shares one buffer between non-fixed types
     * and flushes the previous type whenever another is requested, which killed the cached consumers mid-frame
     * ("Not building!" as soon as fills and outlines alternate). Depth-tested types first, x-ray on top.
     */
    private static final MultiBufferSource.BufferSource OWN = MultiBufferSource.immediateWithBuffers(
        new java.util.LinkedHashMap<>(java.util.Map.of()) {{
            put(FILL_DEPTH, new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
            put(LINES_DEPTH, new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
            put(FILL_XRAY, new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
            put(LINES_XRAY, new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
        }}, new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 12));

    private static MultiBufferSource.BufferSource source;
    private static PoseStack.Pose pose;
    private static Vec3 cam = Vec3.ZERO;
    private static VertexConsumer fillX, fillD, lineX, lineD;
    private static final Matrix4f VIEW = new Matrix4f(), PROJ = new Matrix4f();
    private static float lineWidth = 1.5f;

    private Render3D() {}

    public static void begin(LevelRenderContext ctx) {
        source = OWN;
        pose = ctx.poseStack().last();
        var camera = ctx.levelState().cameraRenderState;
        cam = camera.pos;
        VIEW.set(camera.viewRotationMatrix);
        PROJ.set(camera.projectionMatrix);
        fillX = fillD = lineX = lineD = null;
        Overlay.beginWorld(); // StreamProof: boxes go to the hidden layer, which needs the world's depth
    }

    public static void end() {
        if (source == null) return;
        source.endBatch();
    }

    private static VertexConsumer fill(boolean xray) {
        if (xray) return fillX != null ? fillX : (fillX = source.getBuffer(FILL_XRAY));
        return fillD != null ? fillD : (fillD = source.getBuffer(FILL_DEPTH));
    }

    private static VertexConsumer lines(boolean xray) {
        if (xray) return lineX != null ? lineX : (lineX = source.getBuffer(LINES_XRAY));
        return lineD != null ? lineD : (lineD = source.getBuffer(LINES_DEPTH));
    }

    private static void v(VertexConsumer b, double x, double y, double z, int color) {
        b.addVertex(pose, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(color);
    }

    // ---- shapes -----------------------------------------------------------------------------------------

    public static void box(AABB box, int fill, int outline, boolean throughWalls) {
        if (source == null) return;
        if (ColorUtil.alpha(fill) > 0) filled(fill(throughWalls), box, fill);
        if (ColorUtil.alpha(outline) > 0) outline(lines(throughWalls), box, outline);
    }

    public static void plate(AABB box, int color, double height, boolean throughWalls) {
        if (source == null) return;
        VertexConsumer b = fill(throughWalls);
        int top = ColorUtil.withAlpha(color, 0);
        double y0 = box.minY, y1 = box.minY + height;
        v(b, box.minX, y0, box.minZ, color); v(b, box.maxX, y0, box.minZ, color);
        v(b, box.maxX, y0, box.maxZ, color); v(b, box.minX, y0, box.maxZ, color);
        if (height > 0) {
            side(b, box.minX, box.minZ, box.maxX, box.minZ, y0, y1, color, top);
            side(b, box.maxX, box.minZ, box.maxX, box.maxZ, y0, y1, color, top);
            side(b, box.maxX, box.maxZ, box.minX, box.maxZ, y0, y1, color, top);
            side(b, box.minX, box.maxZ, box.minX, box.minZ, y0, y1, color, top);
        }
        VertexConsumer l = lines(throughWalls);
        int lc = ColorUtil.withAlpha(color, Math.min(255, ColorUtil.alpha(color) * 3));
        line(l, box.minX, y0, box.minZ, box.maxX, y0, box.minZ, lc);
        line(l, box.maxX, y0, box.minZ, box.maxX, y0, box.maxZ, lc);
        line(l, box.maxX, y0, box.maxZ, box.minX, y0, box.maxZ, lc);
        line(l, box.minX, y0, box.maxZ, box.minX, y0, box.minZ, lc);
    }

    private static void side(VertexConsumer b, double x1, double z1, double x2, double z2, double y0, double y1, int bottom, int top) {
        v(b, x1, y0, z1, bottom); v(b, x2, y0, z2, bottom); v(b, x2, y1, z2, top); v(b, x1, y1, z1, top);
    }

    public static void line(Vec3 a, Vec3 b, int color) {
        if (source == null) return;
        line(lines(true), a.x, a.y, a.z, b.x, b.y, b.z, color);
    }

    /** Line from just in front of the camera (so it starts at the crosshair) to a point. */
    public static void tracer(Vec3 to, int color) {
        if (source == null) return;
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        Vec3 from = cam.add(look.scale(0.5));
        line(lines(true), from.x, from.y, from.z, to.x, to.y, to.z, color);
    }

    private static void line(VertexConsumer b, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        float nx = (float) (x2 - x1), ny = (float) (y2 - y1), nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        nx /= len; ny /= len; nz /= len;
        b.addVertex(pose, (float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z)).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
        b.addVertex(pose, (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z)).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
    }

    private static void filled(VertexConsumer b, AABB x, int c) {
        v(b, x.minX, x.minY, x.minZ, c); v(b, x.maxX, x.minY, x.minZ, c); v(b, x.maxX, x.minY, x.maxZ, c); v(b, x.minX, x.minY, x.maxZ, c);
        v(b, x.minX, x.maxY, x.minZ, c); v(b, x.minX, x.maxY, x.maxZ, c); v(b, x.maxX, x.maxY, x.maxZ, c); v(b, x.maxX, x.maxY, x.minZ, c);
        v(b, x.minX, x.minY, x.minZ, c); v(b, x.minX, x.maxY, x.minZ, c); v(b, x.maxX, x.maxY, x.minZ, c); v(b, x.maxX, x.minY, x.minZ, c);
        v(b, x.minX, x.minY, x.maxZ, c); v(b, x.maxX, x.minY, x.maxZ, c); v(b, x.maxX, x.maxY, x.maxZ, c); v(b, x.minX, x.maxY, x.maxZ, c);
        v(b, x.minX, x.minY, x.minZ, c); v(b, x.minX, x.minY, x.maxZ, c); v(b, x.minX, x.maxY, x.maxZ, c); v(b, x.minX, x.maxY, x.minZ, c);
        v(b, x.maxX, x.minY, x.minZ, c); v(b, x.maxX, x.maxY, x.minZ, c); v(b, x.maxX, x.maxY, x.maxZ, c); v(b, x.maxX, x.minY, x.maxZ, c);
    }

    private static void outline(VertexConsumer b, AABB x, int c) {
        line(b, x.minX, x.minY, x.minZ, x.maxX, x.minY, x.minZ, c);
        line(b, x.maxX, x.minY, x.minZ, x.maxX, x.minY, x.maxZ, c);
        line(b, x.maxX, x.minY, x.maxZ, x.minX, x.minY, x.maxZ, c);
        line(b, x.minX, x.minY, x.maxZ, x.minX, x.minY, x.minZ, c);
        line(b, x.minX, x.maxY, x.minZ, x.maxX, x.maxY, x.minZ, c);
        line(b, x.maxX, x.maxY, x.minZ, x.maxX, x.maxY, x.maxZ, c);
        line(b, x.maxX, x.maxY, x.maxZ, x.minX, x.maxY, x.maxZ, c);
        line(b, x.minX, x.maxY, x.maxZ, x.minX, x.maxY, x.minZ, c);
        line(b, x.minX, x.minY, x.minZ, x.minX, x.maxY, x.minZ, c);
        line(b, x.maxX, x.minY, x.minZ, x.maxX, x.maxY, x.minZ, c);
        line(b, x.maxX, x.minY, x.maxZ, x.maxX, x.maxY, x.maxZ, c);
        line(b, x.minX, x.minY, x.maxZ, x.minX, x.maxY, x.maxZ, c);
    }

    /**
     * World position to GUI-scaled screen coordinates using this frame's matrices.
     * @return {x, y} or null when the point is behind the camera.
     */
    public static double[] project(Vec3 world) {
        Vector4f v = new Vector4f((float) (world.x - cam.x), (float) (world.y - cam.y), (float) (world.z - cam.z), 1f);
        v.mul(VIEW);
        v.mul(PROJ);
        if (v.w <= 0.01f) return null;
        double nx = v.x / v.w, ny = v.y / v.w;
        double sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        return new double[]{(nx + 1) / 2 * sw, (1 - ny) / 2 * sh};
    }

    /** Interpolated bounding box for smooth entity ESP. */
    public static AABB lerpBox(Entity e, float delta) {
        Vec3 p = e.getPosition(delta);
        return e.getBoundingBox().move(p.x - e.getX(), p.y - e.getY(), p.z - e.getZ());
    }
}
