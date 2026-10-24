package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.RotationUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

/**
 * Away: an enderman gets angry when the rotation the server has for you points at its eyes (Enderman
 * .isBeingStaredBy: dot of your look and the direction to its eye above 1 - 0.025/distance, with line of sight,
 * within 16 blocks, no carved pumpkin). When the rotation you'd send does that, a low-priority silent rotation
 * looks just below its feet instead; your camera stays where it is. At: looks at the nearest enderman's eyes to
 * pull it (enderman farms).
 */
public class EndermanLook extends Module {
    public final ModeSetting mode = mode("Mode", "Away", "Away = never anger them. At = stare at the nearest one to pull it.", "Away", "At");
    public final NumberSetting range = num("Range", 32, 4, 64, 1, "At: how far to look for one.").visibleWhen(() -> mode.is("At"));

    public EndermanLook() { super("EndermanLook", "Never anger endermen by looking at them (or stare to pull them).", Category.WORLD); }

    @Override
    public void onTick() {
        var p = mc.player;
        if (!LivingEntity.PLAYER_NOT_WEARING_DISGUISE_ITEM.test(p)) return; // a carved pumpkin already does it
        if (mode.is("At")) {
            Enderman best = null;
            double bestDist = range.get() * range.get();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof Enderman em && em.isAlive() && p.distanceToSqr(em) < bestDist && p.hasLineOfSight(em)) { best = em; bestDist = p.distanceToSqr(em); }
            }
            if (best != null) Prism.rotations().request(new Vec3(best.getX(), best.getEyeY(), best.getZ()), 5);
            return;
        }
        // the rotation we'll send this tick: whatever another module asked for, else the camera
        var rot = Prism.rotations();
        float yaw = rot.isActive() ? rot.getYaw() : p.getYRot(), pitch = rot.isActive() ? rot.getPitch() : p.getXRot();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Enderman em) || !em.isAlive() || p.distanceToSqr(em) > 256) continue;
            if (!staredAt(em, yaw, pitch)) continue;
            // look at the ground just short of it: below its eye cone at any distance
            Vec3 down = new Vec3(em.getX(), em.getY() - 1.5, em.getZ());
            float[] r = RotationUtil.toward(down);
            rot.request(r[0], Math.max(r[1], pitch + 1), 1);
            return;
        }
    }

    /** Enderman.isLookingAtMe(player, 0.025, true, false, eyeY) with a given rotation. */
    private boolean staredAt(Enderman em, float yaw, float pitch) {
        var p = mc.player;
        Vec3 look = RotationUtil.direction(yaw, pitch).normalize();
        Vec3 dir = new Vec3(em.getX() - p.getX(), em.getEyeY() - p.getEyeY(), em.getZ() - p.getZ());
        double dist = dir.length();
        if (dist < 1e-4) return false;
        if (look.dot(dir.normalize()) <= 1.0 - 0.025 / dist) return false;
        Vec3 eye = p.getEyePosition();
        return mc.level.clip(new ClipContext(eye, new Vec3(em.getX(), em.getEyeY(), em.getZ()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType()
            == net.minecraft.world.phys.HitResult.Type.MISS;
    }
}
