package dev.prismglass.module.render;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Tints the slots holding the items you care about (totems, crystals, gapples by default) in every inventory and
 * container, so they jump out when you loot or sort. Drawn behind the item by SlotHighlightMixin.
 */
public class ItemHighlight extends Module {
    public final ListSetting items = list("Items", "totem_of_undying,end_crystal,enchanted_golden_apple,elytra", "Items to highlight.", ListSetting.Kind.ITEM);
    public final ColorSetting color = color("Color", 0x60B197FC, "Slot tint.");
    public final BoolSetting border = bool("Border", true, "Draw a border too.");
    public final BoolSetting enchanted = bool("Enchanted", false, "Also highlight any enchanted item.");

    public ItemHighlight() { super("ItemHighlight", "Highlights chosen items in inventories and containers.", Category.RENDER); }

    private String lastQuery;
    private java.util.Set<net.minecraft.world.item.Item> targets = java.util.Set.of();

    public boolean wants(ItemStack s) {
        if (s.isEmpty()) return false;
        if (enchanted.get() && s.isEnchanted()) return true;
        if (!items.get().equals(lastQuery)) {
            // parsed once per change: this runs for every slot every frame
            lastQuery = items.get();
            java.util.Set<net.minecraft.world.item.Item> set = new java.util.HashSet<>();
            for (String id : items.items()) {
                var ident = net.minecraft.resources.Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (ident != null && BuiltInRegistries.ITEM.containsKey(ident)) set.add(BuiltInRegistries.ITEM.getValue(ident));
            }
            targets = set;
        }
        return targets.contains(s.getItem());
    }

    /** Called by SlotHighlightMixin before the slot's item is drawn (screen-relative slot coordinates). */
    public void draw(GuiGraphicsExtractor g, Slot slot) {
        if (!wants(slot.getItem())) return;
        int c = color.color();
        g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, c);
        if (border.get()) g.outline(slot.x - 1, slot.y - 1, 18, 18, ColorUtil.withAlpha(c, 0xE0));
    }
}
