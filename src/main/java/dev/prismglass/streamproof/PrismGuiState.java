package dev.prismglass.streamproof;

import java.util.function.Consumer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/** Added to GuiRenderState by GuiRenderStateMixin: where Prism's (overlay) strata begin. */
public interface PrismGuiState {
    /** Starts a new stratum and marks it as the first overlay one (no-op if already marked this frame). */
    void prism$markOverlay();

    int prism$overlayStratum();

    /** ClickGUI PopOut: starts a new stratum and marks it as the first hologram one (no-op if already marked). */
    void prism$markHolo();

    int prism$holoStratum();

    int prism$blurStratum();

    int prism$strataCount();

    void prism$forEachElementIn(int start, int end, Consumer<GuiElementRenderState> consumer);
}
