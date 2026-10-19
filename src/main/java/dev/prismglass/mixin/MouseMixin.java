package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void prism$onMouse(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        int button = info.button();
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || mc.screen != null || mc.player == null) return;
        // left/right click stay vanilla; buttons 3+ (middle, side buttons) are bindable
        if (button < 2) return;
        if (action == GLFW.GLFW_PRESS) Prism.modules().onKey(KeyUtil.MOUSE_OFFSET + button, true);
        else if (action == GLFW.GLFW_RELEASE) Prism.modules().onKey(KeyUtil.MOUSE_OFFSET + button, false);
    }

    @org.spongepowered.asm.mixin.Unique private double prism$lastX, prism$lastY;
    @org.spongepowered.asm.mixin.Unique private boolean prism$hasLast;

    /** Hologram pop-out: a locked mouse steers your head, so the crosshair can sweep the floating panel. */
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void prism$onMove(long window, double x, double y, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || mc.player == null || !dev.prismglass.gui.render.Holo.steering()) {
            prism$hasLast = false;
            return;
        }
        if (!prism$hasLast) { prism$lastX = x; prism$lastY = y; prism$hasLast = true; ci.cancel(); return; }
        double dx = x - prism$lastX, dy = y - prism$lastY;
        prism$lastX = x; prism$lastY = y;
        double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
        double f = sens * sens * sens * 8.0; // vanilla turnPlayer's curve
        mc.player.turn(dx * f * (mc.options.invertMouseX().get() ? -1 : 1), dy * f * (mc.options.invertMouseY().get() ? -1 : 1));
        ci.cancel();
    }
}
