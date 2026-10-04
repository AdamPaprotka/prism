package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.combat.AutoArmor;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Takes items out of chests / barrels / shulkers with a human-like randomised delay.
 *
 * <p>AutoEquip: armour that beats what you wear is taken first; once the container closes it is equipped
 * right away (through the InventoryGuard, so it never clicks while the server thinks you're moving).
 * EquipEarly closes the container as soon as the armour is out, for the fastest gear-up.
 */
public class ChestStealer extends Module {
    public final NumberSetting delay = num("Delay", 110, 0, 500, 5, "ms between items.");
    public final NumberSetting random = num("Random", 40, 0, 200, 5, "Random extra ms per item.");
    public final NumberSetting openDelay = num("OpenDelay", 150, 0, 1000, 10, "ms to wait after the container opens.");
    public final ModeSetting filter = mode("Filter", "Useful", "Which items to take.", "Useful", "All");
    public final BoolSetting autoClose = bool("AutoClose", true, "Close when empty or your inventory is full.");
    public final BoolSetting autoEquip = bool("AutoEquip", true, "Take better armour first and equip it.");
    public final BoolSetting equipEarly = bool("EquipEarly", false, "Close right after the armour is taken (fastest gear-up).");

    private static final Set<Item> JUNK = Set.of(Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.SPIDER_EYE, Items.WHEAT_SEEDS,
        Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.DEAD_BUSH, Items.BONE, Items.STICK, Items.FEATHER,
        Items.FLINT, Items.CLAY_BALL, Items.KELP, Items.SEAGRASS, Items.SHORT_GRASS, Items.TALL_GRASS, Items.FERN);

    private AbstractContainerScreen<?> screen;
    private long nextClick;
    private boolean equipPending;
    private long nextEquip;

    public ChestStealer() { super("ChestStealer", "Steals container contents (and equips better armour).", Category.PLAYER); }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (mc.screen instanceof ContainerScreen || mc.screen instanceof ShulkerBoxScreen) {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) mc.screen;
            if (s != screen) { screen = s; nextClick = now + openDelay.getInt(); }
            if (now < nextClick) return;
            steal(s.getMenu(), now);
            return;
        }
        screen = null;
        if (equipPending && mc.screen == null && now >= nextEquip) {
            AutoArmor armor = Prism.modules().get(AutoArmor.class);
            if (armor.findUpgrade() == null) { equipPending = false; return; }
            if (!InvUtil.canClick()) return;
            armor.equipBest();
            nextEquip = now + delay.getInt();
        }
    }

    private void steal(AbstractContainerMenu handler, long now) {
        int containerSize = handler.slots.size() - 36;
        int slot = -1;
        boolean armorLeft = false;
        if (autoEquip.get()) {
            slot = bestArmorSlot(handler, containerSize);
            armorLeft = slot != -1;
        }
        if (slot == -1) {
            if (equipEarly.get() && equipPending) { mc.player.closeContainer(); return; }
            for (int i = 0; i < containerSize; i++) {
                ItemStack st = handler.getSlot(i).getItem();
                if (st.isEmpty() || (filter.is("Useful") && JUNK.contains(st.getItem()))) continue;
                slot = i;
                break;
            }
        }
        if (slot == -1 || inventoryFull(handler.getSlot(Math.max(slot, 0)).getItem())) {
            if (autoClose.get()) mc.player.closeContainer();
            return;
        }
        mc.gameMode.handleContainerInput(handler.containerId, slot, 0, ContainerInput.QUICK_MOVE, mc.player);
        if (armorLeft) equipPending = true;
        int r = random.getInt();
        nextClick = now + delay.getInt() + (r > 0 ? ThreadLocalRandom.current().nextInt(r + 1) : 0);
    }

    /** Container slot holding armour better than what we wear (and better than what we already carry). */
    private int bestArmorSlot(AbstractContainerMenu handler, int containerSize) {
        AutoArmor armor = Prism.modules().get(AutoArmor.class);
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot es : slots) {
            double have = armor.score(mc.player.getItemBySlot(es), es);
            for (int i = 0; i < 36; i++) have = Math.max(have, armor.score(mc.player.getInventory().getItem(i), es));
            for (int i = 0; i < containerSize; i++) {
                if (armor.score(handler.getSlot(i).getItem(), es) > have) return i;
            }
        }
        return -1;
    }

    private boolean inventoryFull(ItemStack incoming) {
        if (mc.player.getInventory().getFreeSlot() != -1) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(s, incoming) && s.getCount() < s.getMaxStackSize()) return false;
        }
        return true;
    }
}
