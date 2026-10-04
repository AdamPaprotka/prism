package dev.prismglass.util;

import dev.prismglass.Prism;
import dev.prismglass.module.combat.Hitboxes;
import dev.prismglass.module.player.Reach;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The hitbox a server-side anticheat actually accepts.
 *
 * <p>Grim's Reach check doesn't test the target where your client draws it. It tests the union of every
 * position the target can occupy while your client interpolates toward the latest server position
 * ({@code ReachInterpolationData#getPossibleLocationCombined}). For a moving player the newest server
 * position is usually 0.2-0.5 blocks ahead of the drawn one, and a hit anywhere in that union is legal.
 * We rebuild the part of that union we can know for sure (drawn box + box at the newest server position),
 * which is always inside Grim's own union, so hits against it can't flag.
 *
 * <p>Grim also adds a 0.03 hitbox margin when your last movement packets carried no position (you stood
 * still), plus its 0.0005 threshold; {@link #margin()} returns that.
 */
public final class HitboxUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private HitboxUtil() {}

    /** Drawn box unioned with the box at the entity's newest server position. */
    public static AABB serverBox(Entity e) {
        AABB drawn = e.getBoundingBox();
        Vec3 server;
        if (e instanceof LivingEntity le) server = le.getInterpolation().position();
        else server = e.getPositionCodec().getBase();
        if (server.distanceToSqr(e.position()) > 16) return drawn; // teleported: don't trust stale data
        AABB atServer = drawn.move(server.subtract(e.position()));
        return drawn.minmax(atServer);
    }

    /** Extra hitbox margin Grim grants: 0.0005 threshold, +0.03 while we stood still last tick. */
    public static double margin() {
        var p = mc.player;
        boolean idle = p.getX() == p.xo && p.getY() == p.yo && p.getZ() == p.zo;
        return 0.0005 + (idle ? 0.03 : 0.0);
    }

    /** The box combat code should aim at / measure against, per the AntiCheat profile. */
    public static AABB attackBox(Entity e) {
        AABB box = Prism.anticheat().interpReach.get() ? serverBox(e) : e.getBoundingBox();
        return box.inflate(margin() - 0.0005 * 0.5); // stay a hair inside Grim's own margin
    }

    // ---- crosshair --------------------------------------------------------------------------------

    /**
     * After vanilla picked the crosshair target: if Hitboxes(Grim) or Reach(Grim/NCP) is on, re-test players
     * against the server-accepted box and the allowed range, and take the nearest one the look ray hits.
     */
    public static void extendCrosshair(float tickDelta) {
        if (mc.player == null || mc.level == null) return;
        Hitboxes hb = Prism.modules().get(Hitboxes.class);
        Reach reach = Prism.modules().get(Reach.class);
        boolean useServerBox = hb != null && hb.isEnabled() && hb.mode.is("Grim");
        boolean customReach = reach != null && reach.isEnabled() && !reach.mode.is("Vanilla");
        if (!useServerBox && !customReach) return;

        double range = customReach ? reach.allowedRange() : mc.player.entityInteractionRange();
        Vec3 eye = mc.player.getEyePosition(tickDelta);
        Vec3 end = eye.add(mc.player.getViewVector(tickDelta).scale(range));

        double best = range;
        HitResult current = mc.hitResult;
        if (current != null && current.getType() != HitResult.Type.MISS) best = Math.min(best, eye.distanceTo(current.getLocation()));
        if (current instanceof EntityHitResult) return; // vanilla already found something closer

        Entity pick = null;
        Vec3 pickPos = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.LivingEntity p) || p == mc.player || p.isSpectator() || !p.isAlive()) continue;
            if (mc.player.distanceToSqr(p) > (range + 3) * (range + 3)) continue;
            AABB box = (useServerBox ? serverBox(p) : p.getBoundingBox()).inflate(margin() * 0.9);
            Optional<Vec3> hit = box.contains(eye) ? Optional.of(eye) : box.clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.distanceTo(hit.get());
            if (d <= best) { best = d; pick = p; pickPos = hit.get(); }
        }
        if (pick != null) {
            mc.hitResult = new EntityHitResult(pick, pickPos);
            mc.crosshairPickEntity = pick;
        }
    }
}
