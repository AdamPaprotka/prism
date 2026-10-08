package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.phys.AABB;

/**
 * Spears (26.1): a stab only lands between the spear's min and max reach (2 to 4.5 blocks) and needs full charge,
 * and holding right click charges a lunge that hurts whatever you run into fast enough. SpearKill aims at the
 * nearest enemy and stabs the moment they're in that window; Charge holds the lunge while you sprint at them.
 * The stab goes along the server's view of your look, so it only fires once the silent rotation ray hits them.
 */
public class SpearKill extends Module {
    public final BoolSetting autoSwap = bool("AutoSwap", true, "Switch to a spear in your hotbar when an enemy is in spear range.");
    public final BoolSetting charge = bool("Charge", true, "Hold the spear charge while sprinting at an enemy (kinetic damage).");
    public final BoolSetting mobs = bool("Mobs", false, "Also target hostile mobs.");

    private boolean charging;
    private int chargeTicks;

    public SpearKill() { super("SpearKill", "Auto spear stabs at the edge of reach + charged lunges.", Category.COMBAT); }

    private static boolean isSpear(ItemStack s) { return s.has(DataComponents.PIERCING_WEAPON); }

    @Override
    public void onDisable() { stopCharge(); }

    private void stopCharge() {
        if (charging) mc.options.keyUse.setDown(false);
        charging = false;
        chargeTicks = 0;
    }

    @Override
    public void onTick() {
        var p = mc.player;
        LivingEntity t = target(8);
        if (t == null) { stopCharge(); return; }
        ItemStack held = p.getMainHandItem();
        double dist = p.getEyePosition().distanceTo(t.getBoundingBox().getCenter());
        if (!isSpear(held)) {
            int slot = InvUtil.findHotbar(SpearKill::isSpear);
            if (!autoSwap.get() || slot == -1 || dist > 6 || !Prism.guard().canSwitchSlot()) { stopCharge(); return; }
            InvUtil.swap(slot, false);
            return; // the swap resets the charge; stab once it's full again
        }
        // aim at them all the time so the stab (and the lunge) go their way
        float[] rot = RotationUtil.toward(t.getBoundingBox().getCenter());
        Prism.rotations().request(rot[0], rot[1], 80);

        AttackRange range = held.getOrDefault(DataComponents.ATTACK_RANGE, new AttackRange(2f, 4.5f, 2f, 6.5f, 0.125f, 0.5f));
        AABB box = t.getBoundingBox().inflate(range.hitboxMargin());
        double ray = Prism.rotations().rayDistance(box, range.maxReach());
        boolean inWindow = ray >= range.minReach() && ray <= range.maxReach() - 0.05;

        if (!charging && inWindow && p.getAttackStrengthScale(0f) >= 1f && Prism.guard().canInteractEntity(t.getId())) {
            PiercingWeapon pw = held.get(DataComponents.PIERCING_WEAPON);
            mc.gameMode.piercingAttack(pw);
            p.swing(InteractionHand.MAIN_HAND);
            p.resetAttackStrengthTicker();
            return;
        }

        // lunge: sprinting at them from a few blocks out, keep the charge held until we're through
        if (charge.get()) {
            boolean approach = p.isSprinting() && dist < 8 && dist > 1.5;
            if (approach && !charging) { charging = true; chargeTicks = 0; }
            if (charging) {
                mc.options.keyUse.setDown(true);
                if (++chargeTicks > 40 || !approach) stopCharge();
            }
        }
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

    @Override public String getInfo() { return charging ? "Charge" : null; }
}
