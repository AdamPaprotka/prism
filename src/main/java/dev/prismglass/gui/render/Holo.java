package dev.prismglass.gui.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.prismglass.Prism;
import dev.prismglass.gui.ClickGuiScreen;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.util.Render3D;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Hologram pop-out (26.1+): the ClickGUI is captured to a texture and drawn on a perspective quad floating in the
 * live world, so the menu literally leans out in front of you.
 *
 * <ul>
 *   <li>ClickGuiScreen marks its first stratum; GuiRendererMixin draws everything before it (vanilla HUD, Prism
 *       HUD) as usual (screen, or the StreamProof overlay) and everything after it (the ClickGUI) into
 *       {@link #target}.</li>
 *   <li>{@link #renderWorld} draws a slightly curved, leaned-back quad textured with that target where it was
 *       placed when the GUI opened (one frame behind the GUI pass, which runs after the world). With StreamProof on
 *       it goes into the overlay, using the world's camera, so it lines up and stays hidden from capture.</li>
 *   <li>Once the panel is really drawn ({@link #live}), the OS mouse is locked and turns your head (MouseMixin);
 *       {@link #cursorGui} raycasts the crosshair onto the panel and ClickGuiScreen uses that point for hover,
 *       clicks, scrolls and drags. Otherwise the ClickGUI stays flat with a free mouse.</li>
 * </ul>
 */
public final class Holo {
    private static final Minecraft mc = Minecraft.getInstance();
    public static final Identifier TEX_ID = Identifier.fromNamespaceAndPath("prismglass", "holo");

    /** Textured quads (GUI textured snippet: projection, transforms, Sampler0) with our shader, no depth test or write. */
    public static final RenderPipeline HOLO = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/holo_quad"))
        .withVertexShader(Identifier.fromNamespaceAndPath("prismglass", "core/holo"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("prismglass", "core/holo"))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .withCull(false)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
        .build();

    /** 26.3: no buffer sources/output targets any more, the panel is staged and drawn by hand (see Render3D.Batch). */
    private static final Render3D.Batch BATCH = new Render3D.Batch("Prism holo", 2048, HOLO);
    private static int holoState; // 0 = not tried, 1 = compiled, -1 = failed

    /** Offscreen target the ClickGUI is captured into; exposed to the render system as TEX_ID. */
    public static TextureTarget target;
    private static GpuTextureView view;
    private static boolean registered;

    private Holo() {}

    public static ClickGui theme() {
        return Prism.modules() == null ? null : Prism.modules().get(ClickGui.class);
    }

    /** The ClickGUI is open with PopOut enabled (whether or not the panel could actually be drawn). */
    public static boolean active() {
        ClickGui t = theme();
        return t != null && t.popOut.get() && mc.gui.screen() instanceof ClickGuiScreen && mc.player != null && mc.level != null;
    }

    /** Set once the GUI pass has drawn into the target since the panel was opened (no stale frame from last time). */
    private static boolean captured;
    /**
     * The world hook ran this frame / the frame before. 26.1 extracts the GUI before it renders the world, so the GUI
     * side looks at either: a hook that stopped running drops us back to the flat GUI within a frame.
     */
    private static boolean worldThisFrame, worldLastFrame;
    /** The last GUI pass went into the hologram, i.e. the panel is what you see right now. */
    private static boolean live;

    /** GuiRendererMixin, once per frame at the GUI draw: was the ClickGUI captured into the hologram this frame? */
    public static void guiFrameDone(boolean capturedNow) {
        if (capturedNow) captured = true;
        live = capturedNow && active();
        worldLastFrame = worldThisFrame;
        worldThisFrame = false;
    }

    /**
     * The panel is really on screen: only then is the mouse locked to steer your head and input mapped onto it.
     * If the world hook or the capture can't run (no world render this frame, some other mod in the way), the
     * ClickGUI stays the normal flat one with a free mouse.
     */
    public static boolean live() { return live && active(); }

    /** Make/resize the capture target to the window and keep it addressable as TEX_ID. */
    private static void ensureTarget() {
        var main = mc.gameRenderer.mainRenderTarget();
        if (target == null) {
            target = new TextureTarget("prism holo", main.width, main.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            view = RenderSystem.getDevice().createTextureView(target.getColorTexture());
        } else if (target.width != main.width || target.height != main.height) {
            if (view != null) view.close();
            target.resize(main.width, main.height);
            view = RenderSystem.getDevice().createTextureView(target.getColorTexture());
            captured = false; // the new texture is empty until the next GUI pass
        }
        if (!registered) {
            mc.getTextureManager().register(TEX_ID, new AbstractTexture() {
                @Override public GpuTexture getTexture() { return target == null ? null : target.getColorTexture(); }
                @Override public GpuTextureView getTextureView() { return view; }
                @Override public GpuSampler getSampler() { return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR); }
                @Override public void close() {}
            });
            registered = true;
        }
    }

    // Anchored plane: placed once when the ClickGUI opens and fixed in the world from then on, so turning your head
    // sweeps the crosshair across it. Only the idle sway moves it (a few cm around the anchor); HoloFace turns it to
    // face you as you walk around it.
    private static boolean anchored;
    private static Vec3 aBase, aCenter, aRight, aUp, aToward;
    private static double aW, aH;
    private static final long OPENED = System.nanoTime();

    /** Place the panel in front of where you're looking (level, facing you, at eye height). Called on open. */
    public static void anchor() {
        ClickGui t = theme();
        var p = mc.player;
        if (p == null) return;
        Vec3 eye = p.getEyePosition();
        Vec3 fwd = Vec3.directionFromRotation(0f, p.getYRot()).normalize(); // horizontal, where you look
        // HoloDepth = perspective strength: a closer panel (same apparent size) makes the far edge shrink more
        double depth = t == null ? 0.55 : t.holoDepth.getFloat();
        double dist = 1.4 + (1 - depth) * 2.6;
        // HoloSize 1 = 60% of your view's height whatever your FOV, so the text stays readable at FOV 110 too
        double fov = Math.toRadians(mc.options.fov().get());
        aH = 2 * dist * Math.tan(fov / 2) * 0.6 * (t == null ? 1f : t.holoSize.getFloat());
        var win = mc.getWindow();
        aW = aH * Math.max(1, win.getWidth()) / Math.max(1, win.getHeight()); // same aspect as the captured GUI
        aBase = aCenter = eye.add(fwd.scale(dist));
        orient(fwd);
        faceYaw = yawOf(fwd);
        lastFace = System.nanoTime();
        anchored = true;
    }

    /** Panel axes for a horizontal "you -> panel" direction: leaned back by HoloTilt, curve bending toward you. */
    private static void orient(Vec3 fwd) {
        ClickGui t = theme();
        Vec3 right = fwd.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(fwd).normalize();
        // lean back: the top edge goes away from you
        double tilt = Math.toRadians(t == null ? 24 : t.holoTilt.getFloat());
        Vec3 planeUp = up.scale(Math.cos(tilt)).add(fwd.scale(Math.sin(tilt))).normalize();
        aRight = right;
        aUp = planeUp;
        // the curve bends along the panel's own normal (toward you), so it stays independent of aUp: the raycast in
        // cursorGui() splits the hit into (aRight, aUp, aToward) components and needs them orthogonal
        Vec3 n = right.cross(planeUp).normalize();
        aToward = n.dot(fwd) > 0 ? n.scale(-1) : n;
    }

    /**
     * HoloFace: the panel stays where it opened but turns (yaw only) to face your body as you walk around it. It
     * follows your position, never your view direction, so turning your head still sweeps the cursor across it.
     */
    private static void face() {
        long now = System.nanoTime();
        double dt = Math.min(0.25, (now - lastFace) / 1e9); // a hitch never turns it more than a quarter second's worth
        lastFace = now;
        ClickGui t = theme();
        if (t == null || !t.holoFace.get() || mc.player == null || aBase == null) return;
        Vec3 d = aBase.subtract(mc.player.getEyePosition());
        Vec3 flat = new Vec3(d.x, 0, d.z);
        if (flat.lengthSqr() < 0.04) return; // standing inside it: keep the last facing
        double target = yawOf(flat);
        if (held()) { lastYawError = Math.abs(wrap(target - faceYaw)); return; } // Shift/sneak: frozen exactly as it is
        double diff = wrap(target - faceYaw);
        // tween: exponential ease toward you (HoloTurn = how fast, frame-rate independent); 30 = snaps
        double speed = t.holoTurn.get();
        faceYaw = speed >= 30 ? target : faceYaw + diff * (1 - Math.exp(-speed * dt));
        lastYawError = Math.abs(wrap(target - faceYaw));
        orient(new Vec3(-Math.sin(faceYaw), 0, Math.cos(faceYaw)));
    }

    /** Dev holo test: pretend Shift is held. */
    public static boolean testHold;

    /** Sneak key or either Shift held: the panel stops turning so you can aim at it steadily. */
    public static boolean held() {
        if (testHold) return true;
        var sneak = com.mojang.blaze3d.platform.InputConstants.getKey(mc.options.keyShift.saveString());
        boolean sneakDown = sneak.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD && dev.prismglass.util.KeyUtil.isKeyDown(sneak.getValue());
        return sneakDown || dev.prismglass.util.KeyUtil.shiftDown();
    }

    private static double faceYaw;
    private static long lastFace;
    private static double lastYawError;

    /** Dev holo test: degrees still left to turn before the panel faces you. */
    public static double yawErrorDeg() { return Math.toDegrees(lastYawError); }

    /** Yaw (radians, Minecraft convention: 0 = +Z, increasing toward -X) of a horizontal direction. */
    private static double yawOf(Vec3 dir) { return Math.atan2(-dir.x, dir.z); }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** Per frame: idle drift around the fixed anchor; the cursor raycast uses the same centre. */
    private static void sway() {
        face();
        ClickGui t = theme();
        if (t != null && !t.holoSway.get()) { aCenter = aBase; return; }
        double s = (System.nanoTime() - OPENED) / 1e9;
        aCenter = aBase.add(aRight.scale(Math.sin(s * 0.7) * 0.025)).add(aUp.scale(Math.sin(s * 1.1) * 0.035));
    }

    public static boolean anchored() { return anchored; }

    /** Dev holo test: the panel's "toward you" axis right now. */
    public static Vec3 toward() { return aToward; }

    /** Dev holo test: the anchor (without sway). */
    public static Vec3 base() { return aBase; }

    public static void unanchor() { anchored = false; captured = false; live = false; worldLastFrame = false; }

    private static final boolean TEST = Boolean.getBoolean("prismglass.holotest"); // hidden test window is never focused

    public static boolean steering() { return live() && (mc.isWindowActive() || TEST); }

    /** Crosshair raycast onto the anchored panel -> GUI-scaled coords (null if behind the camera). */
    public static double[] cursorGui() {
        if (!anchored) return null;
        ClickGui t = theme();
        double curve = t == null ? 0.1 : t.holoCurve.getFloat();
        var camera = mc.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot()).normalize();

        // Solve the ray against the panel's parabola Y = k*X² in the (aRight, aToward) plane (Y toward you).
        Vec3 rel = eye.subtract(aCenter);
        double ex = rel.dot(aRight), ey = rel.dot(aToward);
        double lx = look.dot(aRight), ly = look.dot(aToward);
        double k = 4 * curve / aW;
        double a = k * lx * lx, b = 2 * k * ex * lx - ly, c = k * ex * ex - ey, tt;
        if (Math.abs(a) < 1e-9) {
            if (Math.abs(b) < 1e-9) return null;
            tt = -c / b;
        } else {
            double disc = b * b - 4 * a * c;
            if (disc < 0) return null;
            double sq = Math.sqrt(disc), t1 = (-b - sq) / (2 * a), t2 = (-b + sq) / (2 * a);
            tt = Double.MAX_VALUE;
            if (t1 > 0.01 && t1 < tt) tt = t1;
            if (t2 > 0.01 && t2 < tt) tt = t2;
            if (tt == Double.MAX_VALUE) return null;
        }
        Vec3 d = eye.add(look.scale(tt)).subtract(aCenter);
        double u = d.dot(aRight) / (aW / 2), v = d.dot(aUp) / (aH / 2); // -1..1
        double gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        return new double[]{(u * 0.5 + 0.5) * gw, (0.5 - v * 0.5) * gh};
    }

    /** Dev holo test: the {yaw, pitch} that puts the crosshair on GUI point (gx, gy) of the panel. */
    public static float[] lookAt(double gx, double gy) {
        if (!anchored) anchor();
        ClickGui t = theme();
        double curve = t == null ? 0.1 : t.holoCurve.getFloat();
        double u = gx / mc.getWindow().getGuiScaledWidth() * 2 - 1, v = 1 - gy / mc.getWindow().getGuiScaledHeight() * 2;
        Vec3 p = aCenter.add(aRight.scale(u * aW / 2)).add(aUp.scale(v * aH / 2)).add(aToward.scale(curve * aW * u * u));
        Vec3 d = p.subtract(mc.gameRenderer.mainCamera().position());
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new float[]{yaw, pitch};
    }

    /**
     * This frame's ClickGUI goes into the hologram target: the world hook ran this frame (so the panel is drawn) and
     * the target exists. Works with StreamProof too: the panel is then drawn into the overlay.
     */
    public static boolean capturing() {
        return active() && anchored && target != null && (worldThisFrame || worldLastFrame);
    }

    /** ClickGuiScreen, before its first element: from here on the GUI goes to the hologram (the HUD under it stays put). */
    public static void markGui() {
        if (capturing()) ((dev.prismglass.streamproof.PrismGuiState) mc.gameRenderer.gameRenderState().guiRenderState).prism$markHolo();
    }

    /**
     * World pass: draw the gently-curved panel textured with the capture target (filled during the GUI pass, one frame
     * behind). Goes to the StreamProof overlay when that's on, with the same camera pose and projection as the world,
     * so it lines up exactly and stays hidden from capture.
     */
    /** Dev holo test: act as if the world hook never runs (the flat fallback must kick in). */
    public static boolean debugNoWorld;

    public static void renderWorld(LevelRenderContext ctx) {
        if (debugNoWorld) return;
        ensureTarget();
        if (!anchored) anchor();
        if (!anchored) return;
        sway();
        worldThisFrame = true;
        // liquid glass refracts this snapshot: take it before the panel is drawn, or the glass shows last frame's
        // panel (doubled, smeared text). GuiRendererMixin skips its own copy while we capture.
        Glass.copySnapshot();
        if (!captured) return; // nothing drawn into it yet this time: don't flash last session's menu
        ClickGui t = theme();
        double curve = t == null ? 0.1 : t.holoCurve.getFloat();

        if (holoState == 0) {
            // compiled on first use; a broken shader must not take the world pass down with it
            try {
                holoState = RenderSystem.getCompiledPipelineNullable(HOLO) != null ? 1 : -1;
            } catch (Throwable e) {
                holoState = -1;
                Prism.LOG.error("[holo] panel shader failed", e);
            }
        }
        if (holoState < 0) return;

        PoseStack.Pose pose = ctx.poseStack().last();
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        Vec3 upHalf = aUp.scale(aH / 2);
        int N = 16;
        BATCH.begin();
        // columns across aRight, each nudged toward the viewer by a parabola so the panel wraps a little
        Vec3 pTop = null, pBot = null;
        float pu = 0;
        for (int i = 0; i <= N; i++) {
            double fx = (double) i / N - 0.5;                 // -0.5..0.5
            double depth = curve * aW * (2 * fx) * (2 * fx);  // 0 at centre, curve*width at the edges
            Vec3 c = aCenter.add(aRight.scale(fx * aW)).add(aToward.scale(depth));
            Vec3 top = c.add(upHalf), bot = c.subtract(upHalf);
            float uu = (float) i / N;
            if (i > 0) {
                VertexConsumer vc = BATCH.get(HOLO);
                // V flipped: the capture target's rows start at the bottom (GL convention) vs the GUI's top-left
                vert(vc, pose, cam, pTop, pu, 1f);
                vert(vc, pose, cam, top, uu, 1f);
                vert(vc, pose, cam, bot, uu, 0f);
                vert(vc, pose, cam, pBot, pu, 0f);
            }
            pTop = top;
            pBot = bot;
            pu = uu;
        }
        // StreamProof on: hidden layer (with the world's camera, so it lines up), else the main target
        panelDrawn = BATCH.draw(() -> "Prism holo panel", Render3D.overlayTarget(), true, "Sampler0", view,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private static boolean panelDrawn;

    /** Dev holo test: the last world pass really drew the panel quad (not just decided to). */
    public static boolean panelDrawn() { return panelDrawn; }

    private static void vert(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vec3 p, float u, float v) {
        vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setUv(u, v);
    }
}
