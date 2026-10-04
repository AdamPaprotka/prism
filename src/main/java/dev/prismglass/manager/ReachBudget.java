package dev.prismglass.manager;

import dev.prismglass.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Mirror of NoCheatPlus' dynamic reach (fight.Reach, default config): limit = 4.02 * reachMod.
 * A hit farther than 4.02 - 0.8 = 3.22 lowers reachMod by 0.14 / 4.02 (floor 3.22 / 4.02),
 * a closer hit raises it back by the same step (cap 1.0). Tracking our own hits the same way tells us
 * exactly how much reach NCP will accept on the next hit.
 */
public final class ReachBudget {
    private static final double SURVIVAL = 4.02, REDUCE_DISTANCE = 0.8, REDUCE_STEP = 0.14;
    private static final double STEP = REDUCE_STEP / SURVIVAL;
    private static final double MIN_MOD = (SURVIVAL - REDUCE_DISTANCE) / SURVIVAL;
    /** NCP measures to the entity position, we measure to the box: keep a safety margin. */
    private static final double SAFETY = 0.15;

    private double reachMod = 1.0;

    public void onAttack(Entity target) {
        if (Minecraft.getInstance().player == null) return;
        double d = RotationUtil.distanceTo(target);
        if (d > SURVIVAL - REDUCE_DISTANCE) reachMod = Math.max(MIN_MOD, reachMod - STEP);
        else reachMod = Math.min(1.0, reachMod + STEP);
    }

    /** Reach NCP will accept for the next hit, with margin. */
    public double ncpAllowed() { return SURVIVAL * reachMod - SAFETY; }

    public void reset() { reachMod = 1.0; }
}
