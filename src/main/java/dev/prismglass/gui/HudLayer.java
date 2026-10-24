package dev.prismglass.gui;

import dev.prismglass.Prism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Where the Prism HUD sits in the GUI's strata. Normally it is the topmost layer, extracted right after vanilla's
 * whole GUI (screen, toasts, debug overlay) by GameRendererMixin. Prism's own screens draw over it instead: they call
 * {@link #under} first thing in their background, so the HUD lands right under them.
 *
 * <p>26.3 moved the GUI extraction (and the screen call the 26.1 mixin hooked) from GameRenderer into
 * {@code Gui.extractRenderState}; doing the "under the screen" case from the screens themselves keeps it in this
 * mod's own classes.
 */
public final class HudLayer {
    private HudLayer() {}

    /** Our own screens draw over the HUD (the HUD editor's outlines must sit on top of it). */
    public static boolean underScreen() {
        var s = Minecraft.getInstance().gui.screen();
        return s instanceof ClickGuiScreen || s instanceof HudEditorScreen || s instanceof ChangelogScreen || s instanceof AboutScreen;
    }

    /**
     * Start of one of those screens' background: the HUD, then a fresh stratum, so the screen's own background (dim,
     * blur) still covers the HUD the way it did when the HUD was extracted before the screen.
     */
    public static void under(GuiGraphicsExtractor ctx) {
        if (!underScreen()) return;
        Prism.renderHud(ctx, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true));
        ctx.nextStratum();
    }
}
