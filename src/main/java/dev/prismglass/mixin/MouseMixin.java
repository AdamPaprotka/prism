package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void prism$onMouse(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        int button = info.button(); // SDL: 1 left, 2 middle, 3 right, 4+ side
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || mc.gui.screen() != null || mc.player == null) return;
        // left/right click stay vanilla; middle and side buttons are bindable
        if (!KeyUtil.isBindableMouse(button)) return;
        if (action == KeyUtil.PRESS) Prism.modules().onKey(KeyUtil.mouse(button), true);
        else if (action == KeyUtil.RELEASE) Prism.modules().onKey(KeyUtil.mouse(button), false);
    }

    /**
     * Hologram pop-out: a locked mouse steers your head, so the crosshair can sweep the floating panel.
     * SDL hands us the relative motion directly (relative mode keeps the absolute position still).
     */
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void prism$onMove(long window, double x, double y, double dx, double dy, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || mc.player == null || !dev.prismglass.gui.render.Holo.steering()) return;
        double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
        double f = sens * sens * sens * 8.0; // vanilla turnPlayer's curve
        if (dx != 0 || dy != 0)
            mc.player.turn(dx * f * (mc.options.invertMouseX().get() ? -1 : 1), dy * f * (mc.options.invertMouseY().get() ? -1 : 1));
        ci.cancel();
    }
}
