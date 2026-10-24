package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.render.CustomCrosshair;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CustomCrosshair: draws ours instead of the vanilla crosshair (first person only, like vanilla). */
@Mixin(Hud.class)
public abstract class CrosshairMixin {
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void prism$crosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        CustomCrosshair cc = Prism.modules().get(CustomCrosshair.class);
        if (cc == null || !cc.isEnabled()) return;
        ci.cancel();
        if (net.minecraft.client.Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            graphics.nextStratum();
            cc.draw(graphics);
        }
    }
}
