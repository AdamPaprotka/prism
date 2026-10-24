package dev.prismglass.mixin;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.gui.render.Holo;
import dev.prismglass.streamproof.Overlay;
import dev.prismglass.streamproof.PrismGuiState;
import dev.prismglass.util.Render3D;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix4f;
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
 * the same way vanilla splits strata around the background blur. The ClickGUI's PopOut hologram is a third part,
 * drawn into Holo's capture target.
 *
 * <p>26.3: GuiRenderer appends one StagedVertexBuffer draw per batch straight into {@code draws} while meshing (no
 * more meshesToDraw/recordDraws), uploads them all between prepare() and draw(), and draw() lost its fog argument.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow @Final private GuiRenderState renderState;
    @Shadow @Final private List<?> draws;
    @Shadow @Final private StagedVertexBuffer vertexBuffer;
    @Shadow private int firstDrawIndexAfterBlur;
    @Shadow @Final private Projection guiProjection;
    @Shadow @Final private ProjectionMatrixBuffer guiProjectionMatrixBuffer;
    @Shadow @Final private static Comparator<GuiElementRenderState> ELEMENT_SORT_COMPARATOR;
    @Shadow private ScreenRectangle previousScissorArea;
    @Shadow private RenderPipeline previousPipeline;
    @Shadow private TextureSetup previousTextureSetup;
    @Shadow private StagedVertexBuffer.Draw previousDraw;

    @Shadow private void preparePictureInPicture() {}
    @Shadow private void prepareItemElements() {}
    @Shadow private void prepareText() {}
    @Shadow private void addElementToMesh(GuiElementRenderState elementState) {}
    @Shadow private void executeDrawRange(Supplier<String> label, RenderTarget target, GpuBufferSlice dynamicTransforms, int startIndex, int endIndex) {}

    /** Draw indices where the overlay / hologram parts start this frame (MAX_VALUE = none). */
    @Unique private int prism$overlayDrawStart = Integer.MAX_VALUE;
    @Unique private int prism$holoDrawStart = Integer.MAX_VALUE;

    /**
     * Up to three parts, in stratum order: main (vanilla GUI), StreamProof overlay (Prism HUD; ClickGUI too when it
     * isn't popped out), hologram (the popped-out ClickGUI, drawn into Holo.target; with StreamProof the panel itself
     * is then drawn into the overlay by Holo.renderWorld).
     */
    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private void prism$prepare(CallbackInfo ci) {
        PrismGuiState st = (PrismGuiState) renderState;
        int overlay = Overlay.active() ? st.prism$overlayStratum() : Integer.MAX_VALUE;
        int holo = Holo.capturing() ? st.prism$holoStratum() : Integer.MAX_VALUE;
        prism$overlayDrawStart = Integer.MAX_VALUE;
        prism$holoDrawStart = Integer.MAX_VALUE;
        if (overlay == Integer.MAX_VALUE && holo == Integer.MAX_VALUE) return;
        ci.cancel();
        preparePictureInPicture();
        prepareItemElements();
        prepareText();
        renderState.sortElements(ELEMENT_SORT_COMPARATOR);
        int blur = st.prism$blurStratum(), count = st.prism$strataCount();
        int holoStart = Math.min(holo, count);
        int mainEnd = Math.min(overlay, holoStart);
        prism$addRange(0, Math.min(blur, mainEnd));
        firstDrawIndexAfterBlur = blur < mainEnd ? draws.size() : Integer.MAX_VALUE;
        if (blur < mainEnd) prism$addRange(blur, mainEnd);
        if (overlay != Integer.MAX_VALUE) {
            prism$overlayDrawStart = draws.size();
            prism$addRange(mainEnd, holoStart);
        }
        if (holo != Integer.MAX_VALUE) {
            prism$holoDrawStart = draws.size();
            prism$addRange(holoStart, count);
        }
        // what vanilla's prepare() ends with: size the shared index buffers before render() uploads
        for (Object draw : draws) vertexBuffer.requestIndexCount(((GuiDrawAccessor) draw).prism$draw());
    }

    @Unique
    private void prism$addRange(int start, int end) {
        if (start >= end) return;
        previousScissorArea = null;
        previousPipeline = null;
        previousTextureSetup = null;
        previousDraw = null;
        ((PrismGuiState) renderState).prism$forEachElementIn(start, end, this::addElementToMesh);
    }

    /** World and title panorama are drawn, the GUI is not: grab the image the liquid glass refracts. */
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void prism$draw(CallbackInfo ci) {
        boolean holo = prism$holoDrawStart != Integer.MAX_VALUE && Holo.capturing();
        boolean overlay = prism$overlayDrawStart != Integer.MAX_VALUE && Overlay.active();
        Holo.guiFrameDone(holo);
        if (!holo) Glass.copySnapshot(); // PopOut: Holo.renderWorld took it before drawing the panel
        if (!holo && !overlay) return;
        ci.cancel();
        if (holo) {
            // the hologram texture is transparent wherever the ClickGUI draws nothing (26.3 depth clears to 0)
            RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(Holo.target.getColorTexture(), new Vector4f(0f), Holo.target.getDepthTexture(), 0.0);
        }
        if (draws.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        WindowRenderState windowState = minecraft.gameRenderer.gameRenderState().windowRenderState;
        guiProjection.setupOrtho(1000.0F, 11000.0F, (float) windowState.width / windowState.guiScale, (float) windowState.height / windowState.guiScale, true);
        RenderSystem.setProjectionMatrix(guiProjectionMatrixBuffer.getBuffer(guiProjection), ProjectionType.ORTHOGRAPHIC);
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f().setTranslation(0.0F, 0.0F, -11000.0F));

        int n = draws.size();
        int holoStart = holo ? Math.min(prism$holoDrawStart, n) : n;
        int mainEnd = overlay ? Math.min(prism$overlayDrawStart, holoStart) : holoStart;
        int preBlurEnd = Math.min(firstDrawIndexAfterBlur, mainEnd);
        if (preBlurEnd > 0) executeDrawRange(() -> "GUI before blur", main, dynamicTransforms, 0, preBlurEnd);
        if (firstDrawIndexAfterBlur < mainEnd) {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(main.getDepthTexture(), 0.0);
            minecraft.gameRenderer.processBlurEffect();
            Glass.copySnapshot();
            executeDrawRange(() -> "GUI after blur", main, dynamicTransforms, firstDrawIndexAfterBlur, mainEnd);
        }
        if (overlay && mainEnd < holoStart) {
            RenderTarget target = Render3D.overlayTarget();
            if (target.getDepthTexture() != null) RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), 0.0);
            executeDrawRange(() -> "Prism StreamProof overlay", target, dynamicTransforms, mainEnd, holoStart);
        }
        if (holo && holoStart < n)
            executeDrawRange(() -> "Prism holo capture", Holo.target, dynamicTransforms, holoStart, n);
    }

    /** Screens with a blurred background: refract the blurred image (vanilla path only; the split path does it itself). */
    @Inject(method = "draw", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V", shift = At.Shift.AFTER))
    private void prism$snapshotBlurred(CallbackInfo ci) {
        Glass.copySnapshot();
    }
}
