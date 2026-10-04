package dev.prismglass.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure rotation maths. All angles are in degrees, Minecraft convention (yaw 0 = +Z). */
public final class RotationUtil {
    private RotationUtil() {}

    public static float[] toward(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
    }

    public static float[] toward(Vec3 to) {
        return toward(Minecraft.getInstance().player.getEyePosition(), to);
    }

    /** Look vector for a rotation - identical to vanilla Entity#getRotationVector. */
    public static Vec3 direction(float yaw, float pitch) {
        float f = pitch * 0.017453292f;
        float g = -yaw * 0.017453292f;
        float h = Mth.cos(g), i = Mth.sin(g);
        float j = Mth.cos(f), k = Mth.sin(f);
        return new Vec3(i * j, -k, h * j);
    }

    /**
     * Point inside the box closest to the eye, slightly shrunk so the ray clearly enters it.
     * Aiming at the closest point instead of the centre minimises the turn needed, which keeps
     * rotation deltas small and natural.
     */
    public static Vec3 closestPoint(AABB box, Vec3 eye) {
        AABB b = box.deflate(box.getXsize() * 0.15, box.getYsize() * 0.15, box.getZsize() * 0.15);
        return new Vec3(
            Mth.clamp(eye.x, b.minX, b.maxX),
            Mth.clamp(eye.y, b.minY, b.maxY),
            Mth.clamp(eye.z, b.minZ, b.maxZ));
    }

    public static double distanceToBox(Vec3 eye, AABB box) {
        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double distanceTo(Entity e) {
        return distanceToBox(Minecraft.getInstance().player.getEyePosition(), e.getBoundingBox());
    }

    /** Angular difference between two rotations. */
    public static float angleDiff(float yaw1, float pitch1, float yaw2, float pitch2) {
        float dy = Math.abs(Mth.wrapDegrees(yaw1 - yaw2));
        float dp = Math.abs(pitch1 - pitch2);
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    /** Mouse sensitivity step: every real mouse turn is a multiple of this. */
    public static float gcd() {
        double sens = Minecraft.getInstance().options.sensitivity().get();
        double f = sens * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }
}
