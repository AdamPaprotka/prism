package dev.prismglass.mixin;

import dev.prismglass.Prism;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void prism$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        int key = event.key();
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || key == GLFW.GLFW_KEY_UNKNOWN) return;
        if (mc.screen != null || mc.player == null) return;
        if (action == GLFW.GLFW_PRESS) Prism.modules().onKey(key, true);
        else if (action == GLFW.GLFW_RELEASE) Prism.modules().onKey(key, false);
    }
}
