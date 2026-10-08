package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.manager.MovementHooks;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/**
 * Mace smashes. A mace hit after falling more than 1.5 blocks deals bonus damage that grows with the fall, so:
 * <ul>
 *   <li>any hit while falling (yours, KillAura's, AutoHit's) swaps to your best mace for that hit;</li>
 *   <li>WindJump: with an enemy close, jump and throw a wind charge straight down to launch yourself;</li>
 *   <li>AutoHit: on the way down, hit the enemy below once you've fallen MinFall.</li>
 * </ul>
 * Fall distance is tracked here (the client never gets the server's): highest point since you last went up.
 */
public class AutoMace extends Module {
    public final BoolSetting windJump = bool("WindJump", true, "Launch yourself with a wind charge when an enemy is close.");
    public final NumberSetting range = num("Range", 4, 2, 8, 0.5, "WindJump: launch when an enemy is this close.");
    public final BoolSetting autoHit = bool("AutoHit", true, "Hit the enemy below you while falling.");
    public final NumberSetting minFall = num("MinFall", 2.5, 1.6, 12, 0.5, "AutoHit: smash once you've fallen this far.");
    public final BoolSetting mobs = bool("Mobs", false, "Also target hostile mobs.");

    private double apexY;
    private int jumpStage, cooldown, airTicks;
    private LivingEntity launchTarget;

    public AutoMace() { super("AutoMace", "Wind charge launch + mace smash (damage grows with the fall).", Category.COMBAT); }

    /** How far we've fallen since the top of the arc. */
    public double fall() {
        var p = mc.player;
        return p.onGround() || p.isFallFlying() || p.isInWater() ? 0 : Math.max(0, apexY - p.getY());
    }

    public boolean canSmash() { return fall() > 1.5 && maceSlot() != -1; }

    private int maceSlot() {
        return WeaponSwap.best(s -> s.is(Items.MACE), s -> 1 + WeaponSwap.enchantLevel(s, Enchantments.DENSITY) * 2 + WeaponSwap.enchantLevel(s, Enchantments.BREACH));
    }

    private LivingEntity target(double r) {
        LivingEntity best = null;
        double bestD = r;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive()) continue;
            boolean ok = e instanceof Player pl ? !Prism.friends().isFriend(pl) && !pl.isSpectator() : mobs.get() && e instanceof Enemy;
            double d = mc.player.distanceTo(e);
            if (ok && d < bestD) { bestD = d; best = le; }
        }
        return best;
    }

    @Override
    public void onTick() {
        var p = mc.player;
        if (cooldown > 0) cooldown--;
        // fall tracking: the arc's top since we last moved up
        if (p.onGround() || p.getDeltaMovement().y > 0 || p.isFallFlying()) apexY = p.getY();

        if (airTicks > 0 && (--airTicks == 0 || p.onGround() && airTicks < 35)) { airTicks = 0; launchTarget = null; }
        LivingEntity t = target(Math.max(range.get(), 6));
        if (windJump.get()) windJump(t);
        if (autoHit.get() && t != null && fall() >= minFall.get() && maceSlot() != -1) {
            Vec3 aim = t.getBoundingBox().getCenter();
            float[] rot = RotationUtil.toward(aim);
            Prism.rotations().request(rot[0], rot[1], 85);
            CombatUtil.attack(t, true); // the smash swap happens in onAttack
        }
    }

    private void windJump(LivingEntity t) {
        var p = mc.player;
        int charge = InvUtil.findHotbar(Items.WIND_CHARGE);
        if (jumpStage == 0) {
            if (t == null || cooldown > 0 || charge == -1 || !p.onGround() || p.distanceTo(t) > range.get() || maceSlot() == -1) return;
            jumpStage = 1;
        }
        // look straight down first; once the server has that look, throw at our feet and jump in the same tick:
        // the blast goes off right under us while the jump is starting (the classic wind charge launch)
        Prism.rotations().request(p.getYRot(), 90f, 90);
        if (++jumpStage > 8 || charge == -1 || !p.onGround()) { jumpStage = 0; cooldown = 20; return; }
        if (Prism.rotations().getServerPitch() < 85f || !WeaponSwap.forThisHit(charge)) return;
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        MovementHooks.requestJump();
        launchTarget = t;
        airTicks = 40;
        jumpStage = 0;
        cooldown = 20;
    }

    /** While airborne after our launch: where to steer (the target's feet), else null. Read by the move hooks. */
    public net.minecraft.world.phys.Vec3 airChasePoint() {
        LivingEntity t = launchTarget;
        if (airTicks == 0 || t == null || !t.isAlive() || mc.player.onGround()) return null;
        return Math.hypot(t.getX() - mc.player.getX(), t.getZ() - mc.player.getZ()) < 0.4 ? null : t.position();
    }

    @Override
    public void onAttack(Entity target) {
        if (fall() <= 1.5) return;
        int slot = maceSlot();
        ItemStack held = mc.player.getMainHandItem();
        if (slot != -1 && !held.is(Items.MACE)) WeaponSwap.forThisHit(slot);
    }

    @Override
    public String getInfo() { return fall() > 1.5 ? String.format("%.1f", fall()) : null; }
}
