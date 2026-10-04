package dev.prismglass.manager;

import dev.prismglass.Prism;
import dev.prismglass.module.client.AntiCheat;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Makes combat timing and aim look human to statistical checks (Vulcan AutoClicker Consistency /
 * Deviation / Average Deviation, KillAura Pattern).
 *
 * <p>Timing: instead of hitting the instant the cooldown is ready (identical gaps, stddev 0) each hit waits
 * an extra delay drawn from a distribution whose mean and spread themselves drift slowly over time, with
 * occasional longer hesitations. That gives realistic spread AND unstable spread statistics, which is what
 * "deviation of the deviation" style checks look for.
 *
 * <p>Aim: the aim point wanders around the hitbox (smooth random walk) instead of locking onto one spot.
 */
public final class Humanizer {
    private final ThreadLocalRandom rnd = ThreadLocalRandom.current();

    // drifting rhythm, in ms
    private double mean = 70, spread = 45;
    private long nextHitAt = -1;
    private boolean waiting;

    // aim wander, normalised -1..1 per axis
    private double ax, ay, az;

    private AntiCheat ac() { return Prism.anticheat(); }

    /**
     * Call when an attack is otherwise allowed. Returns true when the humanised delay has elapsed.
     * The first call after a hit starts the wait.
     */
    public boolean hitReady() {
        if (!ac().humanize.get()) return true;
        long now = System.currentTimeMillis();
        if (!waiting) {
            waiting = true;
            nextHitAt = now + sampleDelay();
        }
        return now >= nextHitAt;
    }

    /** Call right after a hit was sent. */
    public void onHit() {
        waiting = false;
        // slow random walk of the rhythm (a human's pace changes during a fight)
        mean = Mth.clamp(mean + rnd.nextGaussian() * 6, 35, 140);
        spread = Mth.clamp(spread + rnd.nextGaussian() * 5, 20, 80);
    }

    private long sampleDelay() {
        double scale = ac().humanizeAmount.get();
        double d = mean + rnd.nextGaussian() * spread;
        // right-skewed like real reaction times: occasional slow hits, rare long hesitations
        if (rnd.nextDouble() < 0.12) d += 60 + rnd.nextDouble() * 120;
        if (rnd.nextDouble() < 0.04) d += 200 + rnd.nextDouble() * 250;
        return (long) Math.max(0, d * scale);
    }

    /** Aim point inside the box: wanders smoothly, biased toward the part nearest the eye. */
    public Vec3 aimPoint(AABB box, Vec3 eye, Vec3 closest) {
        if (!ac().humanize.get()) return closest;
        ax = Mth.clamp(ax + rnd.nextGaussian() * 0.08, -0.7, 0.7);
        ay = Mth.clamp(ay + rnd.nextGaussian() * 0.06, -0.5, 0.6);
        az = Mth.clamp(az + rnd.nextGaussian() * 0.08, -0.7, 0.7);
        Vec3 c = box.getCenter();
        Vec3 wander = new Vec3(
            c.x + ax * box.getXsize() * 0.35,
            c.y + ay * box.getYsize() * 0.35,
            c.z + az * box.getZsize() * 0.35);
        return closest.lerp(wander, 0.55);
    }
}
