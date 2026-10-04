package dev.prismglass.mixin;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.streamproof.Overlay;
import dev.prismglass.streamproof.PrismGuiState;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Liquid glass snapshot, plus StreamProof's GUI split: every stratum from Prism's first one onward (marked through
 * PrismGuiState) is meshed after the vanilla ones and drawn into the overlay target instead of the main target,
 * the same way vanilla splits strata around the background blur.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow @Final private GuiRenderState renderState;
    @Shadow @Final private List<?> draws;
    @Shadow @Final private List<?> meshesToDraw;
    @Shadow private int firstDrawIndexAfterBlur;
    @Shadow @Final private Projection guiProjection;
    @Shadow @Final private ProjectionMatrixBuffer guiProjectionMatrixBuffer;
    @Shadow @Final private MultiBufferSource.BufferSource bufferSource;
    @Shadow @Final private static Comparator<GuiElementRenderState> ELEMENT_SORT_COMPARATOR;
    @Shadow private ScreenRectangle previousScissorArea;
    @Shadow private RenderPipeline previousPipeline;
    @Shadow private TextureSetup previousTextureSetup;
    @Shadow private BufferBuilder bufferBuilder;

    @Shadow private void preparePictureInPicture() {}
    @Shadow private void prepareItemElements() {}
    @Shadow private void prepareText() {}
    @Shadow private void addElementToMesh(GuiElementRenderState elementState) {}
    @Shadow private void recordMesh(BufferBuilder bufferBuilder, RenderPipeline pipeline, TextureSetup textureSetup, ScreenRectangle scissorArea) {}
    @Shadow private void recordDraws() {}
    @Shadow private void executeDrawRange(Supplier<String> label, RenderTarget target, GpuBufferSlice fogBuffer, GpuBufferSlice dynamicTransforms,
                                          GpuBuffer indexBuffer, VertexFormat.IndexType indexType, int startIndex, int endIndex) {}

    /** Draw index where the overlay part starts this frame (MAX_VALUE = no split). */
    @Unique private int prism$overlayDrawStart = Integer.MAX_VALUE;

    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private void prism$prepare(CallbackInfo ci) {
        PrismGuiState st = (PrismGuiState) renderState;
        int overlay = st.prism$overlayStratum();
        prism$overlayDrawStart = Integer.MAX_VALUE;
        if (overlay == Integer.MAX_VALUE || !Overlay.active()) return;
        ci.cancel();
        bufferSource.endBatch();
        preparePictureInPicture();
        prepareItemElements();
        prepareText();
        renderState.sortElements(ELEMENT_SORT_COMPARATOR);
        int blur = st.prism$blurStratum(), count = st.prism$strataCount();
        int mainEnd = Math.min(overlay, count);
        prism$addRange(0, Math.min(blur, mainEnd));
        firstDrawIndexAfterBlur = blur < mainEnd ? meshesToDraw.size() : Integer.MAX_VALUE;
        if (blur < mainEnd) prism$addRange(blur, mainEnd);
        prism$overlayDrawStart = meshesToDraw.size();
        prism$addRange(mainEnd, count);
        recordDraws();
    }

    @Unique
    private void prism$addRange(int start, int end) {
        if (start >= end) return;
        previousScissorArea = null;
        previousPipeline = null;
        previousTextureSetup = null;
        bufferBuilder = null;
        ((PrismGuiState) renderState).prism$forEachElementIn(start, end, this::addElementToMesh);
        if (bufferBuilder != null) recordMesh(bufferBuilder, previousPipeline, previousTextureSetup, previousScissorArea);
    }

    /** World and title panorama are drawn, the GUI is not: grab the image the liquid glass refracts. */
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void prism$draw(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        Glass.copySnapshot();
        if (prism$overlayDrawStart == Integer.MAX_VALUE || !Overlay.active()) return;
        ci.cancel();
        if (draws.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        WindowRenderState windowState = minecraft.gameRenderer.getGameRenderState().windowRenderState;
        guiProjection.setupOrtho(1000.0F, 11000.0F, (float) windowState.width / windowState.guiScale, (float) windowState.height / windowState.guiScale, true);
        RenderSystem.setProjectionMatrix(guiProjectionMatrixBuffer.getBuffer(guiProjection), ProjectionType.ORTHOGRAPHIC);
        RenderTarget main = minecraft.getMainRenderTarget();
        int maxIndexCount = 0;
        for (Object draw : draws) maxIndexCount = Math.max(maxIndexCount, ((GuiDrawAccessor) draw).prism$indexCount());
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = autoIndices.getBuffer(maxIndexCount);
        VertexFormat.IndexType indexType = autoIndices.type();
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
            .writeTransform(new Matrix4f().setTranslation(0.0F, 0.0F, -11000.0F), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());

        int n = draws.size();
        int mainEnd = Math.min(prism$overlayDrawStart, n);
        int preBlurEnd = Math.min(firstDrawIndexAfterBlur, mainEnd);
        if (preBlurEnd > 0) executeDrawRange(() -> "GUI before blur", main, fogBuffer, dynamicTransforms, indexBuffer, indexType, 0, preBlurEnd);
        if (firstDrawIndexAfterBlur < mainEnd) {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(main.getDepthTexture(), 1.0);
            minecraft.gameRenderer.processBlurEffect();
            Glass.copySnapshot();
            executeDrawRange(() -> "GUI after blur", main, fogBuffer, dynamicTransforms, indexBuffer, indexType, firstDrawIndexAfterBlur, mainEnd);
        }
        if (mainEnd < n) {
            RenderTarget overlay = Overlay.OUTPUT.getRenderTarget();
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(overlay.getDepthTexture(), 1.0);
            executeDrawRange(() -> "Prism StreamProof overlay", overlay, fogBuffer, dynamicTransforms, indexBuffer, indexType, mainEnd, n);
        }
    }

    /** Screens with a blurred background: refract the blurred image (vanilla path only; the split path does it itself). */
    @Inject(method = "draw", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V", shift = At.Shift.AFTER))
    private void prism$snapshotBlurred(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        Glass.copySnapshot();
    }
}
