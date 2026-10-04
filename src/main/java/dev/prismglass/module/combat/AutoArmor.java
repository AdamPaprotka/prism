package dev.prismglass.module.combat;

import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/** Equips your best armour. Score = armour + toughness + protection enchant levels. */
public class AutoArmor extends Module {
    public final NumberSetting delay = num("Delay", 100, 0, 1000, 10, "Delay in ms between moves.");
    public final BoolSetting keepElytra = bool("KeepElytra", true, "Don't replace a worn elytra.");
    public final BoolSetting blastPrio = bool("BlastProt", false, "Prefer blast protection (crystal PvP).");
    private final Timer timer = new Timer();
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public AutoArmor() { super("AutoArmor", "Automatically wears the best armour.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (!timer.passed(delay.get()) || (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen))) return;
        if (findUpgrade() == null || !InvUtil.canClick()) return;
        if (equipBest()) timer.reset();
    }

    /** {inventory slot, armour index} of the first piece worth equipping, or null. */
    public int[] findUpgrade() {
        for (int s = 0; s < 4; s++) {
            EquipmentSlot slot = SLOTS[s];
            ItemStack worn = mc.player.getItemBySlot(slot);
            if (keepElytra.get() && worn.is(Items.ELYTRA)) continue;
            double bestScore = score(worn, slot);
            int bestInv = -1;
            for (int i = 0; i < 36; i++) {
                double sc = score(mc.player.getInventory().getItem(i), slot);
                if (sc > bestScore) { bestScore = sc; bestInv = i; }
            }
            if (bestInv != -1) return new int[]{bestInv, s};
        }
        return null;
    }

    /** Equips one better piece (one step). Callers must check InvUtil.canClick() first. */
    public boolean equipBest() {
        int[] up = findUpgrade();
        if (up == null) return false;
        ItemStack worn = mc.player.getItemBySlot(SLOTS[up[1]]);
        if (worn.isEmpty()) InvUtil.quickMove(InvUtil.toScreenSlot(up[0]));
        else InvUtil.move(InvUtil.toScreenSlot(up[0]), 5 + up[1]);
        return true;
    }

    public double score(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0;
        Equippable eq = stack.get(DataComponents.EQUIPPABLE);
        if (eq == null || eq.slot() != slot) return -1;
        double armor = 0;
        var mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (var e : mods.modifiers()) {
            if (e.attribute().equals(Attributes.ARMOR)) armor += e.modifier().amount();
            else if (e.attribute().equals(Attributes.ARMOR_TOUGHNESS)) armor += e.modifier().amount() * 0.5;
        }
        var ench = stack.get(DataComponents.ENCHANTMENTS);
        if (ench != null) {
            for (var en : ench.keySet()) {
                int lvl = ench.getLevel(en);
                if (en.is(net.minecraft.world.item.enchantment.Enchantments.PROTECTION)) armor += lvl * 0.8;
                if (en.is(net.minecraft.world.item.enchantment.Enchantments.BLAST_PROTECTION)) armor += lvl * (blastPrio.get() ? 1.6 : 0.4);
            }
        }
        return armor + 0.01;
    }
}
