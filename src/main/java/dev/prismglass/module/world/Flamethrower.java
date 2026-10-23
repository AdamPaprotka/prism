package dev.prismglass.module.world;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.BlockUtil;
import dev.prismglass.util.InvUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Items;

/**
 * Cooks food animals where they stand: flint and steel (or a fire charge) on the top of the block under one that
 * isn't burning yet, so the fire lights in its feet and it drops cooked meat. Never next to you, one per tick, and
 * the switch goes back on the next tick.
 */
public class Flamethrower extends Module {
    public final NumberSetting range = num("Range", 4.5, 1, 6, 0.5, "Place range (capped by AntiCheat).");
    public final NumberSetting safeDistance = num("SafeDistance", 2.5, 1.5, 6, 0.5, "Never light fire closer than this to you.");
    public final BoolSetting cows = bool("Cows", true, "Cows and mooshrooms.");
    public final BoolSetting pigs = bool("Pigs", true, "Pigs.");
    public final BoolSetting chickens = bool("Chickens", true, "Chickens.");
    public final BoolSetting sheep = bool("Sheep", true, "Sheep (cooked mutton, the wool burns).");
    public final BoolSetting rabbits = bool("Rabbits", true, "Rabbits.");
    public final BoolSetting babies = bool("Babies", false, "Babies drop nothing: off skips them.");
    public final BoolSetting fireCharges = bool("FireCharges", true, "Use fire charges when there's no flint and steel.");
    public final BoolSetting antiBreak = bool("AntiBreak", true, "Don't use a flint and steel with 1 durability left.");

    public Flamethrower() { super("Flamethrower", "Sets food animals on fire so they drop cooked meat.", Category.WORLD); }

    @Override
    public void onTick() {
        if (mc.screen != null) return;
        int slot = InvUtil.findHotbar(s -> s.is(Items.FLINT_AND_STEEL) && (!antiBreak.get() || s.getMaxDamage() - s.getDamageValue() > 1));
        if (slot == -1 && fireCharges.get()) slot = InvUtil.findHotbar(Items.FIRE_CHARGE);
        if (slot == -1) return;
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Animal a) || !wanted(a)) continue;
            double d = mc.player.distanceTo(a);
            if (d < safeDistance.get() || d > range.get() + 1) continue;
            BlockPos feet = a.blockPosition();
            if (!mc.level.getBlockState(feet).isAir() || mc.level.getBlockState(feet.below()).isAir()) continue;
            if (!BlockUtil.inPlaceRange(feet.below())) continue;
            if (d < bestDist) { bestDist = d; best = a; }
        }
        if (best == null) return;
        BlockUtil.click(best.blockPosition().below(), Direction.UP, slot, true, 20);
    }

    private boolean wanted(Animal a) {
        if (!a.isAlive() || a.isOnFire() || a.isInWater() || (a.isBaby() && !babies.get())) return false;
        if (a instanceof AbstractCow) return cows.get();
        if (a instanceof Pig) return pigs.get();
        if (a instanceof Chicken) return chickens.get();
        if (a instanceof Sheep) return sheep.get();
        if (a instanceof Rabbit) return rabbits.get();
        return false;
    }
}
