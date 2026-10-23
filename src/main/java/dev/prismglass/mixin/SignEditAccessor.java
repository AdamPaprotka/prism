package dev.prismglass.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** AutoSign: the editor's typed lines (sent by removed() when the screen closes). */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditAccessor {
    @Accessor("messages") String[] prism$messages();
}
