package dev.prismglass.mixin;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

/** CraftUtil: every recipe the server told us we know (the collections only exist once the book was opened). */
@Mixin(ClientRecipeBook.class)
public interface ClientRecipeBookAccessor {
    @Accessor("known")
    Map<RecipeDisplayId, RecipeDisplayEntry> prism$known();
}
