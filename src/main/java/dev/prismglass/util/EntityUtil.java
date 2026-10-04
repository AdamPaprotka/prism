package dev.prismglass.util;

import dev.prismglass.Prism;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class EntityUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private EntityUtil() {}

    public record TargetFilter(boolean players, boolean hostiles, boolean animals, boolean invisibles, boolean naked) {}

    public static boolean isValid(Entity e, TargetFilter f) {
        if (!(e instanceof LivingEntity le) || e == mc.player || !e.isAlive() || le.getHealth() <= 0) return false;
        if (e == mc.getCameraEntity() && e != mc.player) return false;
        if (e.isInvisible() && !f.invisibles()) return false;
        if (e instanceof Player p) {
            if (!f.players() || p.isCreative() || p.isSpectator()) return false;
            if (Prism.friends().isFriend(p)) return false;
            if (!f.naked() && isNaked(p)) return false;
            return true;
        }
        if (e instanceof Enemy) return f.hostiles();
        if (e instanceof Animal) return f.animals();
        return false;
    }

    public static boolean isNaked(Player p) {
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) if (!p.getItemBySlot(slot).isEmpty()) return false;
        return true;
    }

    public static List<LivingEntity> targets(double range, TargetFilter filter, String sort) {
        Vec3 eye = mc.player.getEyePosition();
        Comparator<LivingEntity> cmp = switch (sort) {
            case "Health" -> Comparator.comparingDouble(DamageUtil::health);
            case "Angle" -> Comparator.comparingDouble(e -> {
                float[] r = RotationUtil.toward(e.getBoundingBox().getCenter());
                return RotationUtil.angleDiff(mc.player.getYRot(), mc.player.getXRot(), r[0], r[1]);
            });
            default -> Comparator.comparingDouble(e -> RotationUtil.distanceToBox(eye, e.getBoundingBox()));
        };
        return StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false)
            .filter(e -> isValid(e, filter))
            .map(e -> (LivingEntity) e)
            .filter(e -> RotationUtil.distanceToBox(eye, e.getBoundingBox()) <= range)
            .sorted(cmp)
            .toList();
    }

    public static Player closestEnemy(double range) {
        Player best = null;
        double bestDist = range * range;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || Prism.friends().isFriend(p) || p.isCreative() || p.isSpectator()) continue;
            double d = p.distanceToSqr(mc.player);
            if (d <= bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    public static boolean isInHole(Player p) {
        BlockPos pos = p.blockPosition();
        for (var d : BlockUtil.HORIZONTALS) if (BlockUtil.state(pos.relative(d)).canBeReplaced()) return false;
        return !BlockUtil.state(pos.below()).canBeReplaced();
    }

    public static boolean isMoving() {
        return mc.player.input.getMoveVector().y != 0 || mc.player.input.getMoveVector().x != 0;
    }

    /** Horizontal motion along the input direction relative to the given yaw. */
    public static double[] directionSpeed(double speed, float yaw) {
        float forward = mc.player.input.getMoveVector().y;
        float side = mc.player.input.getMoveVector().x;
        if (forward == 0 && side == 0) return new double[]{0, 0};
        if (forward != 0) {
            if (side > 0) yaw += forward > 0 ? -45 : 45;
            else if (side < 0) yaw += forward > 0 ? 45 : -45;
            side = 0;
            forward = forward > 0 ? 1 : -1;
        }
        double rad = Math.toRadians(yaw + 90f);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        return new double[]{forward * speed * cos + side * speed * sin, forward * speed * sin - side * speed * cos};
    }

    public static double baseSpeed() {
        double base = 0.2873;
        var speed = mc.player.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
        if (speed != null) base *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        return base;
    }
}
