package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.CraftUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.HashSet;
import java.util.Set;

/**
 * While a crafting table (or your inventory, for 2x2 recipes) is open, crafts the listed items from what you carry,
 * through the recipe book like clicking a recipe in it, and shift-clicks (or throws) the results. Only recipes you
 * have unlocked can be placed: that's the server's rule.
 */
public class AutoCraft extends Module {
    public final ListSetting items = list("Items", "white_bed,gold_block", "What to craft.", ListSetting.Kind.ITEM);
    public final BoolSetting craftAll = bool("CraftAll", true, "Fill the grid with as many as you can each time (like shift-clicking a recipe).");
    public final BoolSetting drop = bool("Drop", false, "Throw the results instead of keeping them.");
    public final NumberSetting delay = num("Delay", 2, 1, 20, 1, "Ticks to wait for the server between steps.");

    private Set<Item> parsed = Set.of();
    private String lastList = "";
    private int wait;
    public int crafted;

    public AutoCraft() { super("AutoCraft", "Crafts the listed items while a crafting table is open.", Category.PLAYER); }

    private Set<Item> targets() {
        if (!lastList.equals(items.get())) {
            lastList = items.get();
            Set<Item> s = new HashSet<>();
            for (String id : lastList.split(",")) {
                Identifier i = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
                if (i != null && BuiltInRegistries.ITEM.containsKey(i)) s.add(BuiltInRegistries.ITEM.getValue(i));
            }
            parsed = s;
        }
        return parsed;
    }

    @Override
    public void onTick() {
        var grid = CraftUtil.grid();
        if (grid == null) { wait = 0; return; }
        if (wait > 0) { wait--; return; }
        Set<Item> want = targets();
        ItemStack result = grid.getResultSlot().getItem();
        if (!result.isEmpty() && want.contains(result.getItem())) {
            CraftUtil.take(grid, grid.getResultSlot().index, drop.get());
            crafted++;
            wait = delay.getInt();
            return;
        }
        var recipe = CraftUtil.find(grid, s -> want.contains(s.getItem()));
        if (recipe == null) return;
        CraftUtil.place(grid, recipe, craftAll.get());
        wait = delay.getInt();
    }
}
