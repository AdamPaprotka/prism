package dev.prismglass.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.prismglass.streamproof.Overlay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * World-space rendering for 26.1+. Modules push shapes during onRender3D; they go into four draws
 * (filled/lines x see-through/depth-tested) that are drawn in one render pass at the end, so hundreds of ESP boxes
 * cost a handful of draws.
 *
 * <p>Coordinates are absolute world coordinates; the camera offset is applied here (level rendering is
 * camera-relative, with the view rotation in the model-view transform).
 *
 * <p>26.3 (renderpearl): render types lost their output target and there is no immediate buffer source any more, so
 * Render3D stages its own vertices ({@link Batch}) and draws them itself, into the StreamProof overlay or the main
 * target. Depth is reversed now (cleared to 0, GREATER_THAN_OR_EQUAL passes).
 */
public final class Render3D {
    private static final Minecraft mc = Minecraft.getInstance();

    private static RenderPipeline pipeline(String name, RenderPipeline.Snippet snippet, boolean xray) {
        return RenderPipeline.builder(snippet)
            .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/" + name))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(xray ? CompareOp.ALWAYS_PASS : CompareOp.GREATER_THAN_OR_EQUAL, false))
            .build();
    }

    private static final RenderPipeline FILL_XRAY = pipeline("esp_fill_xray", RenderPipelines.DEBUG_FILLED_SNIPPET, true);
    private static final RenderPipeline FILL_DEPTH = pipeline("esp_fill_depth", RenderPipelines.DEBUG_FILLED_SNIPPET, false);
    private static final RenderPipeline LINES_XRAY = pipeline("esp_lines_xray", RenderPipelines.LINES_SNIPPET, true);
    private static final RenderPipeline LINES_DEPTH = pipeline("esp_lines_depth", RenderPipelines.LINES_SNIPPET, false);

    /** Depth-tested draws first, x-ray on top (the order the 26.1 buffer source flushed them in). */
    private static final Batch OWN = new Batch("Prism Render3D", 1 << 18, FILL_DEPTH, LINES_DEPTH, FILL_XRAY, LINES_XRAY);

    private static boolean building;
    private static PoseStack.Pose pose;
    private static Vec3 cam = Vec3.ZERO;
    private static final Matrix4f VIEW = new Matrix4f(), PROJ = new Matrix4f();
    private static float lineWidth = 1.5f;

    private Render3D() {}

    /**
     * Where Prism's visuals go: the StreamProof overlay target while it runs, otherwise the main target. The one
     * place that reads Overlay's output target (Render3D, Holo and the GUI split all go through here).
     */
    public static RenderTarget overlayTarget() {
        return Overlay.OUTPUT.getRenderTarget();
    }

    public static void begin(LevelRenderContext ctx) {
        pose = ctx.poseStack().last();
        var camera = ctx.levelState().cameraRenderState;
        cam = camera.pos;
        VIEW.set(camera.viewRotationMatrix);
        PROJ.set(camera.projectionMatrix);
        OWN.begin();
        building = true;
        Overlay.beginWorld(); // StreamProof: boxes go to the hidden layer, which needs the world's depth
    }

    public static void end() {
        if (!building) return;
        building = false;
        OWN.draw(() -> "Prism Render3D", overlayTarget(), true, null, null, null);
    }

    // Builders are fetched per shape: the staged buffer has one open builder at a time, and asking for another draw
    // finishes the previous one (a cached consumer would be dead by then).
    private static VertexConsumer fill(boolean xray) {
        return OWN.get(xray ? FILL_XRAY : FILL_DEPTH);
    }

    private static VertexConsumer lines(boolean xray) {
        return OWN.get(xray ? LINES_XRAY : LINES_DEPTH);
    }

    private static void v(VertexConsumer b, double x, double y, double z, int color) {
        b.addVertex(pose, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(color);
    }

    // ---- shapes -----------------------------------------------------------------------------------------

    public static void box(AABB box, int fill, int outline, boolean throughWalls) {
        if (!building) return;
        if (ColorUtil.alpha(fill) > 0) filled(fill(throughWalls), box, fill);
        if (ColorUtil.alpha(outline) > 0) outline(lines(throughWalls), box, outline);
    }

    /** One flat quad (4 corners in order) filled with a single colour. */
    public static void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, boolean throughWalls) {
        if (!building) return;
        VertexConsumer vc = fill(throughWalls);
        v(vc, a.x, a.y, a.z, color);
        v(vc, b.x, b.y, b.z, color);
        v(vc, c.x, c.y, c.z, color);
        v(vc, d.x, d.y, d.z, color);
    }

    public static void plate(AABB box, int color, double height, boolean throughWalls) {
        if (!building) return;
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
        if (!building) return;
        line(lines(true), a.x, a.y, a.z, b.x, b.y, b.z, color);
    }

    /** Line from just in front of the camera (so it starts at the crosshair) to a point. */
    public static void tracer(Vec3 to, int color) {
        if (!building) return;
        var camera = mc.gameRenderer.mainCamera();
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

    // ---- staged drawing -------------------------------------------------------------------------------

    /**
     * A per-frame set of draws, one per pipeline in a fixed order, staged in a StagedVertexBuffer (pooled GPU
     * buffers) and drawn in a single render pass with the current projection, fog and model-view (vanilla's way of
     * drawing its own geometry in 26.3, cf. GuiRenderer). Ask {@link #get} for the builder every time.
     */
    public static final class Batch {
        private final StagedVertexBuffer buffer;
        private final RenderPipeline[] pipelines;
        private final List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
        private boolean open;

        public Batch(String label, int capacity, RenderPipeline... pipelines) {
            this.buffer = new StagedVertexBuffer(() -> label, capacity);
            this.pipelines = pipelines;
        }

        /** Start a frame's worth of geometry (anything left over from a frame that died is dropped). */
        public void begin() {
            if (open) discard();
            for (RenderPipeline p : pipelines) draws.add(buffer.appendDraw(p.getVertexFormatBinding(0), p.getPrimitiveTopology()));
            open = true;
        }

        public VertexConsumer get(RenderPipeline pipeline) {
            for (int i = 0; i < pipelines.length; i++) if (pipelines[i] == pipeline) return buffer.getVertexBuilder(draws.get(i));
            throw new IllegalArgumentException("pipeline not in this batch: " + pipeline);
        }

        public boolean isOpen() { return open; }

        private void discard() {
            try {
                buffer.upload(); // frees the staged vertex slices
            } finally {
                buffer.endFrame();
                draws.clear();
                open = false;
            }
        }

        /**
         * Upload and draw everything into {@code target} (with its depth when {@code depth}), then reset. An optional
         * texture is bound as {@code samplerName} for every draw. Returns whether anything was drawn.
         */
        public boolean draw(Supplier<String> label, RenderTarget target, boolean depth, @Nullable String samplerName,
                            @Nullable GpuTextureView texture, @Nullable GpuSampler sampler) {
            if (!open) return false;
            try {
                // upload first: it finishes the open vertex builder, so only then does the last draw know its vertices
                buffer.upload();
                boolean any = false;
                for (StagedVertexBuffer.Draw d : draws) {
                    buffer.requestIndexCount(d);
                    any |= !d.isEmpty();
                }
                if (!any || target.getColorTextureView() == null) return false;
                RenderSystem.resizeAllAutoStorageIndexBuffers();
                GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
                GpuTextureView depthView = depth && target.hasDepth() ? target.getDepthTextureView() : null;
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(label, target.getColorTextureView(), Optional.empty(), depthView, OptionalDouble.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transforms);
                    if (samplerName != null && texture != null) pass.setUniform(samplerName, texture, sampler);
                    for (int i = 0; i < pipelines.length; i++) {
                        StagedVertexBuffer.ExecuteInfo info = buffer.getExecuteInfo(draws.get(i));
                        if (info == null) continue;
                        pass.setPipeline(RenderSystem.getCompiledPipeline(pipelines[i]));
                        pass.setVertexBuffer(0, info.vertexBuffer().slice());
                        pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                        pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                    }
                }
                return true;
            } finally {
                buffer.endFrame();
                draws.clear();
                open = false;
            }
        }
    }
}
