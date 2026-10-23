package dev.prismglass.module.movement;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.manager.WalkGoal;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.combat.KillAura;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.Vec3;

/**
 * Forward holds W. Behind walks around the target and keeps you behind it, all through real WASD presses
 * (MovementHooks picks the key combination relative to the yaw your movement actually uses, so Grim's
 * prediction sees an ordinary player).
 *
 * <p>Behind is an orbit: on the target's back half you head straight for the spot {@link #distance} behind
 * its head yaw and hold it; on its front half you go round on a wider ring ({@link #frontDistance}, outside a
 * player's 3.0 reach) instead of cutting across its face: you run along the ring's tangent, leaning out of it when
 * the target closes in and back in when you drift wide. While KillAura aims at them that is mostly plain A / D
 * (sprinting needs W), about 5 degrees of orbit a tick at 2.4 blocks: someone turning faster than that can keep
 * facing you, they just never get to stop turning, and you stay out of their reach in front of them.
 *
 * <p>Mobs don't need to face you to hit: they hit when their box, inflated by sqrt(2.04)-0.6 (or their spear's
 * attack range), touches yours. MobSpacing keeps the ring outside that, still inside your own reach.
 */
public class AutoWalk extends Module implements WalkGoal {
    public final ModeSetting mode = mode("Mode", "Forward", "Forward = hold W. Behind = circle the target and stay behind it, out of its view and reach.", "Forward", "Behind");
    public final NumberSetting distance = num("Distance", 2.4, 1.2, 3.2, 0.05, "Behind: where to stand behind the target (centre to centre). Keep it under your attack range.").visibleWhen(this::behind);
    public final NumberSetting frontDistance = num("FrontDistance", 3.6, 2.5, 6, 0.1, "Behind: ring radius while passing in front of the target (3.4+ is outside a player's reach).").visibleWhen(this::behind);
    public final NumberSetting cone = num("Cone", 30, 5, 90, 1, "Behind: within this many degrees of their back counts as behind; you hold the spot there.").visibleWhen(this::behind);
    public final ModeSetting side = mode("Side", "Auto", "Behind: which way round. Auto = the shorter way, switching when one side is blocked.", "Auto", "Left", "Right").visibleWhen(this::behind);
    public final NumberSetting predict = num("Predict", 2, 0, 6, 1, "Behind: lead the target's movement by this many ticks.").visibleWhen(this::behind);
    public final BoolSetting mobSpacing = bool("MobSpacing", true, "Behind: stay outside a mob's melee reach (mobs hit by distance, not by facing).").visibleWhen(this::behind);
    public final NumberSetting range = num("Range", 10, 3, 32, 1, "Behind: targets up to this far. KillAura's target wins while KillAura is on.").visibleWhen(this::behind);
    public final BoolSetting players = bool("Players", true, "Behind: walk behind players.").visibleWhen(this::behind);
    public final BoolSetting hostiles = bool("Hostiles", true, "Behind: walk behind monsters.").visibleWhen(this::behind);
    public final BoolSetting animals = bool("Animals", false, "Behind: walk behind animals.").visibleWhen(this::behind);
    public final BoolSetting sprint = bool("Sprint", true, "Behind: sprint while moving forward (only where vanilla allows it).").visibleWhen(this::behind);
    public final BoolSetting jump = bool("Jump", true, "Behind: jump up 1-block steps in the way.").visibleWhen(this::behind);
    public final BoolSetting avoidHazards = bool("AvoidHazards", true, "Behind: never step off a drop of more than 3 blocks, into lava, fire, cactus or the void, or into a wall.").visibleWhen(this::behind);
    public final BoolSetting render = bool("Render", true, "Behind: draw the ring around the target, its back arc and where you're heading.").visibleWhen(this::behind);
    public final ColorSetting color = color("Color", 0xC04DABF7, "Behind: ring colour (the back arc is drawn brighter).").visibleWhen(() -> behind() && render.get());

    /** Mob melee reach without an attack range component (Mob.DEFAULT_ATTACK_REACH). */
    private static final double MOB_REACH = Math.sqrt(2.04F) - 0.6F;
    /** Within this many degrees of their back (and near the ring) we walk straight onto the spot. */
    private static final double LEAD = 50;
    /** Degrees of lean (out of the ring when too close, into it when too far) per block of radius error. */
    private static final double RING_GAIN = 70;

    private LivingEntity target;
    private Vec3 goal;
    private int orbitSide, blockedTicks, flipCooldown;
    private boolean holding;

    public AutoWalk() { super("AutoWalk", "Holds forward, or walks round behind your target (real input, safe).", Category.MOVEMENT); }

    public boolean behind() { return mode.is("Behind"); }

    @Override public void onDisable() { target = null; goal = null; orbitSide = 0; }

    @Override
    public void onTick() {
        if (!behind()) { target = null; goal = null; return; }
        target = pickTarget();
        if (target == null) { goal = null; orbitSide = 0; }
    }

    private LivingEntity pickTarget() {
        KillAura ka = Prism.modules().get(KillAura.class);
        if (ka != null && ka.isEnabled()) {
            LivingEntity t = ka.getTarget() != null ? ka.getTarget() : ka.chaseTarget();
            if (t != null && t.isAlive() && mc.player.distanceTo(t) <= range.get()) return t;
        }
        EntityUtil.TargetFilter f = new EntityUtil.TargetFilter(players.get(), hostiles.get(), animals.get(), true, true);
        List<LivingEntity> list = EntityUtil.targets(range.get(), f, "Distance");
        return list.isEmpty() ? null : list.get(0);
    }

    // ---- WalkGoal ---------------------------------------------------------------------------------------

    @Override
    public Vec3 walkPoint() {
        if (!behind() || target == null || !target.isAlive()) return null;
        return goal = behindPoint(target);
    }

    @Override public boolean walkSprint() { return sprint.get(); }
    @Override public boolean walkJump() { return jump.get(); }
    @Override public boolean walkAvoidHazards() { return avoidHazards.get(); }
    @Override public double walkStop() { return holding ? 0.35 : 0.15; }

    @Override
    public void walkBlocked() {
        // walled off or every step is a drop: try going round the other way
        if (++blockedTicks >= 6 && flipCooldown == 0 && side.is("Auto")) {
            orbitSide = -orbitSide;
            blockedTicks = 0;
            flipCooldown = 40;
        }
    }

    /**
     * The point to walk to this tick to get (and stay) behind {@code t}. Also used by KillAura's WalkMode Behind,
     * with these settings, so it works without AutoWalk switched on.
     */
    public Vec3 behindPoint(LivingEntity t) {
        int forced = side.is("Left") ? 1 : side.is("Right") ? -1 : 0;
        return orbitPoint(t, distance.get(), frontDistance.get(), forced, true);
    }

    /**
     * The orbit itself, also used by Damage AI Learn's tactics. {@code forcedSide} 0 = the shorter way round, 1 /
     * -1 = always clockwise / counter-clockwise (seen from above); {@code seekBack} false = never settle behind them,
     * keep circling.
     */
    public Vec3 orbitPoint(LivingEntity t, double radius, double frontRadius, int forcedSide, boolean seekBack) {
        if (flipCooldown > 0) flipCooldown--; // called once per tick, whether AutoWalk, KillAura or the AI asks
        Vec3 me = mc.player.position();
        Vec3 tp = t.position();
        int lead = predict.getInt();
        if (lead > 0) tp = tp.add((t.getX() - t.xo) * lead, 0, (t.getZ() - t.zo) * lead);

        // headings in the atan2(z, x) frame; a yaw looks along heading yaw + 90
        double back = Mth.wrapDegrees(t.getYHeadRot() - 90);
        double phi = Math.toDegrees(Math.atan2(me.z - tp.z, me.x - tp.x));
        double delta = Mth.wrapDegrees(back - phi);
        double dist = Math.hypot(me.x - tp.x, me.z - tp.z);
        double abs = Math.abs(delta);

        double r = Math.max(radius, minRadius(t));
        double front = Math.max(frontRadius, r);
        if (mobSpacing.get() && t instanceof Mob) front = r; // a mob hits by distance alone: no point in a wider front ring

        holding = seekBack && abs <= cone.get();
        if (holding) {
            blockedTicks = 0;
            return at(tp, back, r, t.getY());
        }

        int s = forcedSide != 0 ? (orbitSide = forcedSide) : chooseSide(delta);
        // radius: the wide ring in front of them, easing into the close one over their flanks
        double ring = seekBack ? Mth.lerp(Mth.clamp((abs - 60) / 60, 0, 1), r, front) : r;

        if (dist > ring + 1.5) {
            // still coming in: on their back half go straight for the spot, else for the tangent of the ring
            if (seekBack && abs <= 90) return at(tp, back, r, t.getY());
            double tangent = Math.toDegrees(Math.acos(Mth.clamp(ring / dist, -1, 1)));
            return at(tp, phi + s * tangent, ring, t.getY());
        }
        // close to the spot: walk straight onto it
        if (seekBack && abs <= LEAD && Math.abs(dist - r) < 0.6) return at(tp, back, r, t.getY());
        // on the ring: run along it (tangent), leaning out when too close and in when too far. A chord to a point
        // further round would cut inside the ring, and a target walking at you then closes the gap every tick.
        double err = ring - dist;
        double lean = Mth.clamp(err * RING_GAIN, -60, 80);
        double heading = phi + s * (90 - lean);
        return at(me, heading, 2, t.getY());
    }

    /** Which way round: the shorter way, but keep going the way we started unless the other way is much shorter. */
    private int chooseSide(double delta) {
        int shorter = delta >= 0 ? 1 : -1;
        if (orbitSide == 0) orbitSide = shorter;
        // near their face both ways are about as long: don't flip-flop there
        else if (orbitSide != shorter && Math.abs(delta) < 120 && flipCooldown == 0) orbitSide = shorter;
        return orbitSide;
    }

    /** Closest we may stand: outside a mob's melee box when MobSpacing is on. */
    private double minRadius(LivingEntity t) {
        if (!mobSpacing.get() || !(t instanceof Mob mob)) return 0;
        AttackRange ar = mob.getActiveItem().get(DataComponents.ATTACK_RANGE);
        double reach = ar == null ? MOB_REACH : ar.effectiveMaxRange(mob);
        // their box inflated by the reach must not touch ours on either axis, plus a margin for their step toward us
        return t.getBbWidth() / 2 + reach + mc.player.getBbWidth() / 2 + 0.35;
    }

    private static Vec3 at(Vec3 centre, double headingDeg, double radius, double y) {
        double rad = Math.toRadians(headingDeg);
        return new Vec3(centre.x + Math.cos(rad) * radius, y, centre.z + Math.sin(rad) * radius);
    }

    // ---- render -----------------------------------------------------------------------------------------

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        LivingEntity t = target;
        if (!behind() || !render.get() || t == null || !t.isAlive()) return;
        Vec3 c = t.getPosition(delta).add(0, 0.05, 0);
        double r = Math.max(distance.get(), minRadius(t));
        double back = Mth.wrapDegrees(Mth.rotLerp(delta, t.yHeadRotO, t.yHeadRot) - 90);
        int base = color.color();
        int bright = ColorUtil.withAlpha(base, 0xFF);
        int seg = 48;
        Vec3 prev = null;
        for (int i = 0; i <= seg; i++) {
            double a = i * 360.0 / seg;
            Vec3 p = at(c, a, r, c.y);
            if (prev != null) {
                boolean inCone = Math.abs(Mth.wrapDegrees(a - 360.0 / seg / 2 - back)) <= cone.get();
                Render3D.line(prev, p, inCone ? bright : base);
            }
            prev = p;
        }
        // the spot behind them and where we are heading right now
        Vec3 spot = at(c, back, r, c.y);
        Render3D.line(c, spot, ColorUtil.withAlpha(base, 0x60));
        Vec3 g = goal;
        if (g != null) Render3D.box(new net.minecraft.world.phys.AABB(g.x - 0.12, c.y, g.z - 0.12, g.x + 0.12, c.y + 0.24, g.z + 0.12), ColorUtil.withAlpha(base, 0x50), bright, false);
    }

    @Override
    public String getInfo() {
        if (!behind()) return null;
        if (target == null) return "Behind";
        return holding ? "Behind" : "Circling";
    }
}
