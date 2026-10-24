package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;

/**
 * Eats golden apples for you, through the real use key like AutoEat (so it is a normal eat): when low, when burning
 * without Fire Resistance (only an enchanted apple gives it), when your absorption is gone with an enemy close, or
 * when Regeneration ran out while hurt. Once a bite starts it is held to the end, then the slot goes back.
 */
public class AutoGap extends Module {
    public final NumberSetting health = num("Health", 12, 0, 36, 0.5, "Eat at or below this health + absorption (0 = off).");
    public final BoolSetting burning = bool("Burning", true, "Eat an enchanted apple when on fire without Fire Resistance.");
    public final BoolSetting absorption = bool("Absorption", false, "Eat when your absorption is gone and an enemy is within CombatRange.");
    public final NumberSetting combatRange = num("CombatRange", 8, 2, 16, 0.5, "Absorption: how close an enemy has to be.").visibleWhen(() -> absorption.get());
    public final BoolSetting regeneration = bool("Regeneration", false, "Eat when Regeneration ran out and you're not at full health.");
    public final BoolSetting preferEnchanted = bool("PreferEnchanted", true, "Use enchanted golden apples first.");
    public final BoolSetting offhand = bool("Offhand", true, "Eat a gapple held in the offhand without switching (when the main hand item can't be used).");

    private int prevSlot = -1;
    private boolean eating, sawUse;
    private String reason;

    public AutoGap() { super("AutoGap", "Eats golden apples when low, burning, or out of absorption/regeneration.", Category.PLAYER); }

    @Override public void onDisable() { stop(); }

    @Override
    public void onTick() {
        var p = mc.player;
        if (eating) {
            // hold the bite to the end: releasing early wastes it
            if (p.isUsingItem()) { sawUse = true; mc.options.keyUse.setDown(true); return; }
            if (sawUse || ++waitTicks > 5) stop(); else mc.options.keyUse.setDown(true);
            return;
        }
        AutoEat eat = Prism.modules().get(AutoEat.class);
        if ((eat != null && eat.isEating()) || p.isUsingItem() || mc.gui.screen() != null) return;

        boolean needEnchanted = false;
        reason = null;
        if (health.get() > 0 && DamageUtil.health(p) <= health.get()) reason = "Health";
        else if (burning.get() && p.isOnFire() && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) { reason = "Fire"; needEnchanted = true; }
        else if (absorption.get() && p.getAbsorptionAmount() <= 0 && EntityUtil.closestEnemy(combatRange.get()) != null) reason = "Absorption";
        else if (regeneration.get() && !p.hasEffect(MobEffects.REGENERATION) && p.getHealth() < p.getMaxHealth()) reason = "Regen";
        if (reason == null) return;

        // offhand gapple: eat it in place if the main hand has nothing that would be used first
        boolean offEnchanted = p.getOffhandItem().is(Items.ENCHANTED_GOLDEN_APPLE);
        boolean offGap = offEnchanted || p.getOffhandItem().is(Items.GOLDEN_APPLE);
        if (offhand.get() && offGap && (!needEnchanted || offEnchanted) && mainHandPassive()) {
            start(-1);
            return;
        }
        int slot = findGap(needEnchanted);
        if (slot == -1 || !Prism.guard().canSwitchSlot()) return;
        start(slot);
    }

    private int waitTicks;

    /** Start eating: from the hotbar slot (switching to it), or -1 = the offhand gapple in place. */
    private void start(int slot) {
        if (slot != -1) {
            prevSlot = mc.player.getInventory().getSelectedSlot();
            if (slot != prevSlot) InvUtil.swap(slot, false); else prevSlot = -1;
        }
        eating = true;
        sawUse = false;
        waitTicks = 0;
        mc.options.keyUse.setDown(true);
    }

    private int findGap(boolean needEnchanted) {
        int ench = InvUtil.findHotbar(Items.ENCHANTED_GOLDEN_APPLE);
        if (needEnchanted) return ench;
        int gap = InvUtil.findHotbar(Items.GOLDEN_APPLE);
        if (preferEnchanted.get()) return ench != -1 ? ench : gap;
        return gap != -1 ? gap : ench;
    }

    /** Main hand item that right-click won't use (so vanilla falls through to the offhand). */
    private boolean mainHandPassive() {
        var s = mc.player.getMainHandItem();
        return s.isEmpty() || CombatUtil.isWeapon(s) || s.is(net.minecraft.tags.ItemTags.PICKAXES) || s.is(net.minecraft.tags.ItemTags.SHOVELS);
    }

    private void stop() {
        if (!eating) return;
        eating = false;
        mc.options.keyUse.setDown(false);
        if (prevSlot != -1) InvUtil.restore(prevSlot);
        prevSlot = -1;
    }

    public boolean isEating() { return eating; }

    @Override public String getInfo() { return eating ? reason : null; }
}
