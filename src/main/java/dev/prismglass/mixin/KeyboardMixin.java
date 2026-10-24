package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.util.KeyUtil;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void prism$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        int key = event.key(); // SDL scancode
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || key <= 0) return;
        if (mc.gui.screen() != null || mc.player == null) return;
        if (action == KeyUtil.PRESS) Prism.modules().onKey(key, true);
        else if (action == KeyUtil.RELEASE) Prism.modules().onKey(key, false);
    }
}
