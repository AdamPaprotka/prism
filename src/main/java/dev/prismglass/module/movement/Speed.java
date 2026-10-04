package dev.prismglass.module.movement;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.phys.AABB;

/**
 * Speed modes:
 * <ul>
 *   <li>GrimCollide: Grim can't know exactly how much other entities push you, so it tolerates
 *       up to 0.08 b/t extra per colliding entity. We add exactly that toward our input direction.
 *       Only works near players/mobs/boats, but it is prediction-safe.</li>
 *   <li>Strafe: classic NCP bhop with friction-correct air decay. [Grim-unsafe]</li>
 *   <li>Vanilla: flat multiplier. [unsafe on any AC]</li>
 * </ul>
 */
public class Speed extends Module {
    public final ModeSetting mode = mode("Mode", "Strafe", "Movement method.", "Strafe", "GrimCollide", "Vanilla");
    public final NumberSetting vanillaSpeed = num("VanillaSpeed", 1.4, 1, 5, 0.05, "Multiplier for Vanilla mode.");

    private double lastDist, speed;
    private int stage;

    public Speed() { super("Speed", "Move faster.", Category.MOVEMENT); }

    @Override public void onEnable() { stage = 0; lastDist = 0; }

    @Override
    public void onTick() {
        var p = mc.player;
        lastDist = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo);
        if (mode.is("GrimCollide") && EntityUtil.isMoving()) {
            AABB box = p.getBoundingBox().inflate(1.0);
            int count = 0;
            for (var e : mc.level.getEntities(p, box)) {
                if (e instanceof net.minecraft.world.entity.LivingEntity || e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) count++;
            }
            if (count > 0) {
                double[] dir = EntityUtil.directionSpeed(0.08 * Math.min(count, 3), Prism.rotations().getMoveYaw());
                p.push(dir[0], 0, dir[1]);
            }
        }
    }

    @Override
    public void onMove(MoveEvent e) {
        var p = mc.player;
        if (p.isShiftKeyDown() || p.isInWater() || p.isInLava() || p.isFallFlying() || p.getAbilities().flying) return;
        if (!EntityUtil.isMoving()) { stage = 0; return; }
        double base = EntityUtil.baseSpeed();
        switch (mode.get()) {
            case "Vanilla" -> {
                double[] d = EntityUtil.directionSpeed(base * vanillaSpeed.get(), p.getYRot());
                e.setHorizontal(d[0], d[1]);
            }
            case "Strafe" -> {
                if (p.onGround() && stage > 1) stage = 0;
                if (stage == 0 && p.onGround()) {
                    double jump = 0.42;
                    var boost = p.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
                    if (boost != null) jump += (boost.getAmplifier() + 1) * 0.1;
                    e.y = jump;
                    p.setDeltaMovement(p.getDeltaMovement().x, jump, p.getDeltaMovement().z);
                    speed = base * 1.85;
                    stage = 1;
                } else if (stage == 1) {
                    speed = lastDist - 0.66 * (lastDist - base);
                    stage = 2;
                } else {
                    speed = lastDist - lastDist / 159.0;
                }
                speed = Math.max(speed, base);
                double[] d = EntityUtil.directionSpeed(speed, p.getYRot());
                e.setHorizontal(d[0], d[1]);
            }
            default -> {}
        }
    }

    @Override public String getInfo() { return mode.get(); }
}
