package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.InvUtil;
import dev.prismglass.util.KeyUtil;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/**
 * Inventory quality of life. SortKey (middle mouse by default) over a slot sorts that part of the screen: the chest
 * you hover, or your main inventory (never the hotbar or armor), one swap a tick with real clicks. AntiDrop stops Q
 * (and Q in screens) from throwing the listed items; AutoDrop throws the listed junk out of your inventory.
 */
public class InventoryTweaks extends Module {
    public final KeySetting sortKey = key("SortKey", KeyUtil.MOUSE_OFFSET + 2, "Hover a slot and press to sort that inventory.");
    public final BoolSetting antiDrop = bool("AntiDrop", true, "Q can't throw the AntiDropItems.");
    public final ListSetting antiDropItems = list("AntiDropItems", "totem_of_undying,elytra,enchanted_golden_apple,end_crystal", "Items Q won't throw.", ListSetting.Kind.ITEM);
    public final BoolSetting autoDrop = bool("AutoDrop", false, "Throw the AutoDropItems out of your inventory.");
    public final ListSetting autoDropItems = list("AutoDropItems", "rotten_flesh,poisonous_potato,spider_eye", "Junk to throw away.", ListSetting.Kind.ITEM);

    private boolean sortWasDown, ourThrow;
    private List<Integer> sorting; // menu slot ids being sorted
    private int sortContainerId, sortOps;
    private Set<Item> noDrop = Set.of(), junk = Set.of();
    private String lastNoDrop = "", lastJunk = "";

    public InventoryTweaks() { super("InventoryTweaks", "Sort key, AntiDrop and AutoDrop.", Category.PLAYER); }

    private static Set<Item> parse(String list) {
        Set<Item> s = new HashSet<>();
        for (String id : list.split(",")) {
            Identifier i = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
            if (i != null && BuiltInRegistries.ITEM.containsKey(i)) s.add(BuiltInRegistries.ITEM.getValue(i));
        }
        return s;
    }

    /** AntiDropMixin: whether throwing this stack should be stopped. */
    public boolean keep(ItemStack stack) {
        if (!antiDrop.get() || ourThrow || stack.isEmpty()) return false;
        if (!lastNoDrop.equals(antiDropItems.get())) { lastNoDrop = antiDropItems.get(); noDrop = parse(lastNoDrop); }
        return noDrop.contains(stack.getItem());
    }

    @Override
    public void onTick() {
        boolean down = KeyUtil.isDown(sortKey.get());
        if (down && !sortWasDown && mc.gui.screen() instanceof AbstractContainerScreen<?> s) startSort(((dev.prismglass.mixin.ContainerScreenAccessor) s).prism$hoveredSlot());
        sortWasDown = down;
        if (sorting != null) { sortStep(); return; }
        if (autoDrop.get() && (mc.gui.screen() == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) dropJunk();
    }

    /** Sorts the inventory this slot belongs to (your main inventory for any of your own slots). */
    public void startSort(Slot hovered) {
        if (hovered == null) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        List<Integer> region = new ArrayList<>();
        boolean own = hovered.container instanceof Inventory;
        for (Slot s : menu.slots) {
            if (own ? s.container instanceof Inventory && s.getContainerSlot() >= 9 && s.getContainerSlot() < 36 : s.container == hovered.container)
                region.add(s.index);
        }
        if (region.size() < 2) return;
        sorting = region;
        sortContainerId = menu.containerId;
        sortOps = 0;
    }

    /** One swap toward sorted order: by item id, bigger stacks first, empty slots last. */
    private void sortStep() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu.containerId != sortContainerId || !menu.getCarried().isEmpty() || ++sortOps > 300) { sorting = null; return; }
        if (!InvUtil.canClick()) return;
        List<ItemStack> now = new ArrayList<>();
        for (int i : sorting) now.add(menu.getSlot(i).getItem());
        List<ItemStack> want = new ArrayList<>(now);
        want.sort(Comparator.comparing((ItemStack s) -> s.isEmpty()).thenComparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).thenComparing(s -> -s.getCount()));
        for (int i = 0; i < now.size(); i++) {
            if (same(now.get(i), want.get(i))) continue;
            for (int j = i + 1; j < now.size(); j++) {
                if (!same(now.get(j), want.get(i))) continue;
                int a = sorting.get(j), b = sorting.get(i);
                mc.gameMode.handleContainerInput(menu.containerId, a, 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(menu.containerId, b, 0, ContainerInput.PICKUP, mc.player);
                if (!menu.getCarried().isEmpty()) mc.gameMode.handleContainerInput(menu.containerId, a, 0, ContainerInput.PICKUP, mc.player);
                return;
            }
        }
        sorting = null; // done
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b.isEmpty() : !b.isEmpty() && a.getItem() == b.getItem() && a.getCount() == b.getCount();
    }

    private void dropJunk() {
        if (!lastJunk.equals(autoDropItems.get())) { lastJunk = autoDropItems.get(); junk = parse(lastJunk); }
        if (!InvUtil.canClick() || !mc.player.containerMenu.getCarried().isEmpty()) return;
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (!junk.contains(inv.getItem(i).getItem())) continue;
            ourThrow = true;
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, InvUtil.toScreenSlot(i), 1, ContainerInput.THROW, mc.player);
            ourThrow = false;
            return; // one stack a tick
        }
    }

    public boolean sorting() { return sorting != null; }
}
