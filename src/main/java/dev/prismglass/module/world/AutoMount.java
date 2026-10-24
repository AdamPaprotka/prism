package dev.prismglass.module.world;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/**
 * Gets on the closest rideable in range: right-clicks it the vanilla way once the silent rotation faces it.
 * Only things a right-click actually mounts: free boats and rideable minecarts, tamed horse-likes (a saddle is
 * needed to steer, not to sit), saddled pigs and striders. Holding sneak pauses it so you can get off.
 */
public class AutoMount extends Module {
    public final NumberSetting range = num("Range", 4, 1, 6, 0.5, "Reach to the entity's hitbox.");
    public final BoolSetting boats = bool("Boats", true, "Boats and rafts with a free seat.");
    public final BoolSetting minecarts = bool("Minecarts", true, "Rideable minecarts.");
    public final BoolSetting horses = bool("Horses", true, "Tamed horses, donkeys, mules, camels and llamas.");
    public final BoolSetting onlySaddled = bool("OnlySaddled", true, "Horses: only saddled ones (the ones you can steer).").visibleWhen(() -> horses.get());
    public final BoolSetting pigs = bool("Pigs", false, "Saddled pigs and striders.");
    public final NumberSetting delay = num("Delay", 10, 0, 40, 1, "Ticks between attempts (a mount can refuse, e.g. a full boat).");

    private int cooldown;

    public AutoMount() { super("AutoMount", "Mounts nearby boats, minecarts, horses and saddled pigs.", Category.WORLD); }

    @Override
    public void onTick() {
        if (cooldown > 0) { cooldown--; return; }
        var p = mc.player;
        if (p.isPassenger() || p.input.keyPresses.shift() || mc.gui.screen() != null) return;
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!mountable(e)) continue;
            double d = p.distanceToSqr(e);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        if (best == null) return;
        if (EntityUtil.interact(best, 30, range.get())) cooldown = delay.getInt();
    }

    private boolean mountable(Entity e) {
        if (!e.isAlive() || e == mc.player) return false;
        if (e instanceof AbstractBoat b) {
            int seats = b instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat ? 1 : 2;
            return boats.get() && b.getPassengers().size() < seats && !b.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        }
        if (e instanceof AbstractMinecart m) return minecarts.get() && m.isRideable() && !m.isVehicle();
        if (e instanceof AbstractHorse h) {
            if (!horses.get() || h.isVehicle() || h.isBaby()) return false;
            if (h instanceof Camel) return true; // camels take riders untamed; the saddle is only for steering
            if (h instanceof Llama) return h.isTamed();
            return h.isTamed() && (!onlySaddled.get() || h.isSaddled());
        }
        if (e instanceof Pig pig) return pigs.get() && pig.isSaddled() && !pig.isVehicle();
        if (e instanceof Strider s) return pigs.get() && s.isSaddled() && !s.isVehicle();
        return false;
    }
}
