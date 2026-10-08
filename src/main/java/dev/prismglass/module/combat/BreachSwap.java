package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.WeaponSwap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Hit with your sword's charge but a Breach mace's armour piercing: every hit (yours or KillAura's) swaps to the
 * Breach item for that one hit and back next tick. Skipped while AutoMace can smash instead.
 *
 * <p>A hit's strength is (ticks since your last attack) / (the HELD item's charge time), and a mace charges for
 * about 33 ticks against a sword's 12.5. Swapping at sword pace gave ~30% mace hits (tested: 7 damage vs the
 * sword's 19 on a diamond-armoured target), so it only swaps once the mace itself is charged (MinCharge).
 */
public class BreachSwap extends Module {
    public final BoolSetting onlyArmored = bool("OnlyArmored", true, "Only swap when the target wears armour (Breach does nothing otherwise).");
    public final NumberSetting minCharge = num("MinCharge", 0.9, 0.5, 1, 0.05, "Only swap once the mace would hit at least this charged (it charges slower than a sword).");

    private int sinceAttack = 100;

    public BreachSwap() { super("BreachSwap", "Hits use your Breach mace for that hit only (armour piercing).", Category.COMBAT); }

    @Override
    public void onTick() { sinceAttack++; }

    /** Ticks the item in this hotbar slot needs for a full-strength hit. */
    private float chargeTicks(int slot) {
        var stack = mc.player.getInventory().getItem(slot);
        double speed = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
            .compute(Attributes.ATTACK_SPEED, mc.player.getAttributeBaseValue(Attributes.ATTACK_SPEED), EquipmentSlot.MAINHAND);
        return (float) (20.0 / Math.max(0.1, speed));
    }

    @Override
    public void onAttack(Entity target) {
        int since = sinceAttack;
        sinceAttack = 0;
        if (!(target instanceof LivingEntity le) || onlyArmored.get() && le.getArmorValue() == 0) return;
        AutoMace mace = Prism.modules().get(AutoMace.class);
        if (mace != null && mace.isEnabled() && mace.canSmash()) return; // the smash is worth more
        int slot = WeaponSwap.best(s -> WeaponSwap.enchantLevel(s, Enchantments.BREACH) > 0, s -> WeaponSwap.enchantLevel(s, Enchantments.BREACH));
        if (slot != -1 && since >= chargeTicks(slot) * minCharge.get()) WeaponSwap.forThisHit(slot);
    }
}
