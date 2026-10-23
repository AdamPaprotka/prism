package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.render.ItemHighlight;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ItemHighlight: tint behind the slot's item. */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotHighlightMixin {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void prism$highlight(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ItemHighlight ih = Prism.modules().get(ItemHighlight.class);
        if (ih != null && ih.isEnabled()) ih.draw(graphics, slot);
    }
}
