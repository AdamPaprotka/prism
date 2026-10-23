package dev.prismglass.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import java.util.function.Predicate;

/**
 * Crafting through the recipe book, like clicking a recipe in it: the server fills the grid from your inventory
 * (handlePlaceRecipe), then the result is shift-clicked out. Only recipes the server says you know can be placed,
 * which in survival means ones you've unlocked.
 */
public final class CraftUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private CraftUtil() {}

    /** The open crafting grid (crafting table, or your own 2x2 while the inventory is open), or null. */
    public static AbstractCraftingMenu grid() {
        return mc.screen != null && mc.player.containerMenu instanceof AbstractCraftingMenu m ? m : null;
    }

    /** A known recipe whose result matches, that fits the grid and that your inventory can make right now. */
    public static RecipeDisplayEntry find(AbstractCraftingMenu menu, Predicate<ItemStack> result) {
        var known = ((dev.prismglass.mixin.ClientRecipeBookAccessor) mc.player.getRecipeBook()).prism$known();
        StackedItemContents have = new StackedItemContents();
        mc.player.getInventory().fillStackedContents(have);
        menu.getInputGridSlots().forEach(s -> have.accountStack(s.getItem())); // what's already on the grid counts too
        var context = SlotDisplayContext.fromLevel(mc.level);
        for (RecipeDisplayEntry e : known.values()) {
            if (!fits(e, menu.getGridWidth(), menu.getGridHeight())) continue;
            if (e.resultItems(context).stream().noneMatch(result)) continue;
            if (e.canCraft(have)) return e;
        }
        return null;
    }

    private static boolean fits(RecipeDisplayEntry e, int w, int h) {
        if (e.display() instanceof ShapedCraftingRecipeDisplay s) return s.width() <= w && s.height() <= h;
        if (e.display() instanceof ShapelessCraftingRecipeDisplay s) return s.ingredients().size() <= w * h;
        return false;
    }

    /** Asks the server to fill the grid (all it can when max). The result shows up a tick or two later. */
    public static void place(AbstractCraftingMenu menu, RecipeDisplayEntry recipe, boolean max) {
        mc.gameMode.handlePlaceRecipe(menu.containerId, recipe.id(), max);
    }

    /** Takes the result out: shift-click into the inventory, or Q-throw it (ctrl = the whole crafted batch). */
    public static boolean take(AbstractContainerMenu menu, int resultSlot, boolean drop) {
        if (menu.getSlot(resultSlot).getItem().isEmpty()) return false;
        mc.gameMode.handleContainerInput(menu.containerId, resultSlot, drop ? 1 : 0, drop ? ContainerInput.THROW : ContainerInput.QUICK_MOVE, mc.player);
        return true;
    }
}
