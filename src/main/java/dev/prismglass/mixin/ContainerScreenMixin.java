package dev.prismglass.mixin;

import dev.prismglass.module.render.ShulkerPeek;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ShulkerPeek: draw the hovered shulker's contents after the screen. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    @Shadow protected @Nullable Slot hoveredSlot;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void prism$shulkerPeek(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (hoveredSlot != null && hoveredSlot.hasItem()) ShulkerPeek.draw(graphics, mouseX, mouseY, hoveredSlot.getItem());
    }
}
