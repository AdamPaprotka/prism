package dev.prismglass.mixin;

import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** AutoSmelter: what this furnace / smoker / blast furnace accepts (the server syncs the input sets to clients). */
@Mixin(AbstractFurnaceMenu.class)
public interface FurnaceMenuInvoker {
    @Invoker("canSmelt")
    boolean prism$canSmelt(ItemStack stack);

    @Invoker("isFuel")
    boolean prism$isFuel(ItemStack stack);
}
