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
}
