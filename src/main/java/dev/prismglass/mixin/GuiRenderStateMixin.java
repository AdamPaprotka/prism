package dev.prismglass.mixin;

import dev.prismglass.streamproof.PrismGuiState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin implements PrismGuiState {
    @Shadow @Final private List<Object> strata;
    @Shadow private int firstStratumAfterBlur;

    @Shadow public abstract void nextStratum();

    @Shadow public abstract void forEachElement(Consumer<GuiElementRenderState> consumer, GuiRenderState.TraverseRange range);

    @Unique private int prism$overlayStratum = Integer.MAX_VALUE;

    @Override
    public void prism$markOverlay() {
        if (prism$overlayStratum != Integer.MAX_VALUE) return;
        nextStratum();
        prism$overlayStratum = strata.size() - 1;
    }

    @Override public int prism$overlayStratum() { return prism$overlayStratum; }
    @Override public int prism$blurStratum() { return firstStratumAfterBlur; }
    @Override public int prism$strataCount() { return strata.size(); }

    /** Vanilla only traverses "before/after blur"; run its after-blur traversal over a temporarily trimmed list. */
    @Override
    public void prism$forEachElementIn(int start, int end, Consumer<GuiElementRenderState> consumer) {
        int savedBlur = firstStratumAfterBlur;
        List<Object> tail = new ArrayList<>(strata.subList(end, strata.size()));
        strata.subList(end, strata.size()).clear();
        firstStratumAfterBlur = start;
        try {
            forEachElement(consumer, GuiRenderState.TraverseRange.AFTER_BLUR);
        } finally {
            strata.addAll(tail);
            firstStratumAfterBlur = savedBlur;
        }
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void prism$reset(CallbackInfo ci) {
        prism$overlayStratum = Integer.MAX_VALUE;
    }
}
