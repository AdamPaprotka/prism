package dev.prismglass.module.world;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.mixin.FurnaceMenuInvoker;
import dev.prismglass.setting.*;
import dev.prismglass.util.InvUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * While a furnace, smoker or blast furnace is open: tops up the input with things it can cook (all of them, or only
 * the listed ones), tops up the fuel from your fuel list and shift-clicks the results out. One move a tick.
 */
public class AutoSmelter extends Module {
    public final ModeSetting input = mode("Input", "List", "What to cook: the list, or anything this furnace accepts.", "List", "Any");
    public final ListSetting items = list("Items", "raw_iron,raw_gold,raw_copper,ancient_debris,cobblestone,sand,kelp,beef,porkchop,chicken,mutton", "Things to cook in List mode.", ListSetting.Kind.ITEM);
    public final ListSetting fuels = list("Fuel", "coal,charcoal,coal_block,blaze_rod,dried_kelp_block,lava_bucket", "Fuel to use, first one you have.", ListSetting.Kind.ITEM);
    public final BoolSetting takeResults = bool("TakeResults", true, "Shift-click the results into your inventory.");

    private Set<Item> cook = Set.of(), fuel = Set.of();
    private String lastCook = "", lastFuel = "";
    private int wait;

    public AutoSmelter() { super("AutoSmelter", "Fills furnaces with ores + fuel and takes the results while one is open.", Category.WORLD); }

    private static Set<Item> parse(String list) {
        Set<Item> s = new HashSet<>();
        for (String id : list.split(",")) {
            Identifier i = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
            if (i != null && BuiltInRegistries.ITEM.containsKey(i)) s.add(BuiltInRegistries.ITEM.getValue(i));
        }
        return s;
    }

    @Override
    public void onTick() {
        if (mc.gui.screen() == null || !(mc.player.containerMenu instanceof AbstractFurnaceMenu menu)) { wait = 0; return; }
        if (wait > 0) { wait--; return; }
        if (!menu.getCarried().isEmpty() || !InvUtil.canClick()) return;
        if (!lastCook.equals(items.get())) { lastCook = items.get(); cook = parse(lastCook); }
        if (!lastFuel.equals(fuels.get())) { lastFuel = fuels.get(); fuel = parse(lastFuel); }
        FurnaceMenuInvoker f = (FurnaceMenuInvoker) menu;
        if (takeResults.get() && !menu.getSlot(2).getItem().isEmpty()) { click(menu, 2, ContainerInput.QUICK_MOVE); return; }
        if (fill(menu, 0, s -> f.prism$canSmelt(s) && (input.is("Any") || cook.contains(s.getItem())))) return;
        fill(menu, 1, s -> fuel.contains(s.getItem()) && f.prism$isFuel(s));
    }

    /** Moves a matching stack from your inventory into the slot if it's empty or the same item with room. */
    private boolean fill(AbstractContainerMenu menu, int slot, Predicate<ItemStack> ok) {
        ItemStack in = menu.getSlot(slot).getItem();
        if (!in.isEmpty() && in.getCount() >= in.getMaxStackSize()) return false;
        for (Slot s : menu.slots) {
            if (!(s.container instanceof Inventory) || s.getContainerSlot() >= 36) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty() || !ok.test(st) || (!in.isEmpty() && !ItemStack.isSameItemSameComponents(st, in))) continue;
            click(menu, s.index, ContainerInput.PICKUP);
            click(menu, slot, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) click(menu, s.index, ContainerInput.PICKUP); // didn't all fit: put the rest back
            wait = 2;
            return true;
        }
        return false;
    }

    private void click(AbstractContainerMenu menu, int slot, ContainerInput in) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, in, mc.player);
    }
}
