package dev.prismglass.util;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.prismglass.streamproof.Overlay;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Render types for Chams and PopChams: vanilla's translucent entity pipeline (same shader and vertex format, so the
 * model draws exactly like an entity) with the depth test either kept or set to always pass (seen through walls),
 * and no depth writes so the ghost never hides what's behind it.
 *
 * <p>26.3: depth is reversed (GREATER_THAN_OR_EQUAL passes). With Improved Transparency (OIT) on, translucent models
 * are drawn through OIT pipeline sets, which can't see through walls, so the chams are forced into the solid model
 * phase there instead (still blended, drawn after the terrain).
 */
public final class ChamsRender {
    private static final RenderPipeline XRAY = pipeline("chams_xray", true, true);
    private static final RenderPipeline DEPTH = pipeline("chams_depth", false, true);
    private static final RenderPipeline XRAY_HIDDEN = pipeline("chams_xray_hidden", true, false);
    private static final RenderPipeline DEPTH_HIDDEN = pipeline("chams_depth_hidden", false, false);
    /** Keyed by variant (bit 0 through walls, bit 1 OIT/solid phase, bit 2 hidden) then texture. */
    private static final Map<Integer, Map<Identifier, RenderType>> TYPES = new HashMap<>();

    private ChamsRender() {}

    private static RenderPipeline pipeline(String name, boolean xray, boolean visible) {
        return RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/" + name))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withColorTargetState(visible ? new ColorTargetState(BlendFunction.TRANSLUCENT)
                : new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(xray ? CompareOp.ALWAYS_PASS : CompareOp.GREATER_THAN_OR_EQUAL, false))
            .build();
    }

    /** The tinted model pass for an entity texture. */
    public static RenderType type(Identifier texture, boolean throughWalls) {
        boolean oit = Minecraft.getInstance().gameRenderer.useImprovedTransparency();
        // TODO(26.3): render types have no output target any more, so the colour pass can't be routed into the
        //  StreamProof overlay. While the overlay is active it is drawn with colour writes off (hidden from capture,
        //  and from you) instead of leaking onto the stream; the normal model still renders.
        boolean hidden = Overlay.active();
        int variant = (throughWalls ? 1 : 0) | (oit ? 2 : 0) | (hidden ? 4 : 0);
        return TYPES.computeIfAbsent(variant, v -> new HashMap<>()).computeIfAbsent(texture, t -> {
            RenderPipeline pipeline = hidden ? (throughWalls ? XRAY_HIDDEN : DEPTH_HIDDEN) : (throughWalls ? XRAY : DEPTH);
            RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline).withTexture("Sampler0", t).useLightmap().useOverlay();
            setup = oit ? setup.withForcedSolidModelPhase() : setup.sortOnUpload();
            return RenderType.create(throughWalls ? "prism_chams_xray" : "prism_chams", setup.createRenderSetup());
        });
    }
}
