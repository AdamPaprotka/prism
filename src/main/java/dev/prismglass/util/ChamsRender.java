package dev.prismglass.util;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import dev.prismglass.streamproof.Overlay;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Render types for Chams and PopChams: vanilla's translucent entity pipeline (same shader and vertex format, so the
 * model draws exactly like an entity) with the depth test either kept or set to always pass (seen through walls),
 * and no depth writes so the ghost never hides what's behind it. Goes to the StreamProof overlay like ESP.
 */
public final class ChamsRender {
    private static final RenderPipeline XRAY = pipeline("chams_xray", true);
    private static final RenderPipeline DEPTH = pipeline("chams_depth", false);
    private static final Map<Identifier, RenderType> XRAY_TYPES = new HashMap<>(), DEPTH_TYPES = new HashMap<>();

    private ChamsRender() {}

    private static RenderPipeline pipeline(String name, boolean xray) {
        return RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("prismglass", "pipeline/" + name))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withSampler("Sampler1")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(xray ? CompareOp.ALWAYS_PASS : CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();
    }

    /** The tinted model pass for an entity texture. */
    public static RenderType type(Identifier texture, boolean throughWalls) {
        Map<Identifier, RenderType> cache = throughWalls ? XRAY_TYPES : DEPTH_TYPES;
        return cache.computeIfAbsent(texture, t -> RenderType.create(throughWalls ? "prism_chams_xray" : "prism_chams",
            RenderSetup.builder(throughWalls ? XRAY : DEPTH).withTexture("Sampler0", t).useLightmap().useOverlay().sortOnUpload()
                .setOutputTarget(Overlay.OUTPUT).createRenderSetup()));
    }
}
