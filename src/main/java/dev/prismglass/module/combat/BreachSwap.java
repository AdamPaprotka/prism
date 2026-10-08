package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.util.WeaponSwap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Hit with your sword's charge but a Breach mace's armour piercing: every hit (yours or KillAura's) swaps to the
 * Breach item for that one hit and back next tick. Skipped while AutoMace can smash instead.
 */
public class BreachSwap extends Module {
    public final BoolSetting onlyArmored = bool("OnlyArmored", true, "Only swap when the target wears armour (Breach does nothing otherwise).");

    public BreachSwap() { super("BreachSwap", "Hits use your Breach mace for that hit only (armour piercing).", Category.COMBAT); }

    @Override
    public void onAttack(Entity target) {
        if (!(target instanceof LivingEntity le) || onlyArmored.get() && le.getArmorValue() == 0) return;
        AutoMace mace = Prism.modules().get(AutoMace.class);
        if (mace != null && mace.isEnabled() && mace.canSmash()) return; // the smash is worth more
        int slot = WeaponSwap.best(s -> WeaponSwap.enchantLevel(s, Enchantments.BREACH) > 0, s -> WeaponSwap.enchantLevel(s, Enchantments.BREACH));
        if (slot != -1) WeaponSwap.forThisHit(slot);
    }
}
