package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * Attacks nearby targets. Rotation is silent through the RotationManager; a hit is only sent when the
 * rotation the server already has intersects the target hitbox inside the AntiCheat range
 * (Grim Reach/Hitbox), respecting cooldown, min-delay and one-target-per-tick.
 *
 * <p>KB displacement: a melee hit knocks back in two parts. The base 0.4 push always goes straight away from your
 * position, but the sprint / Knockback-enchant push (0.5+) follows the YAW of the hit. So hitting with the yaw turned
 * by KBAngle bends where they fly. On Grim the yaw has to keep pointing into their hitbox, which allows only a few
 * degrees; KBFull uses the whole angle (vanilla / no-anticheat servers).
 */
public class KillAura extends Module {
    public final NumberSetting range = num("Range", 3.0, 1, 6, 0.05, "Attack range (capped by AntiCheat).");
    public final NumberSetting wallRange = num("WallRange", 0, 0, 6, 0.05, "Range through walls (0 = never).");
    public final NumberSetting rotateRange = num("RotateRange", 4.5, 1, 8, 0.1, "Start turning toward targets at this range.");
    public final BoolSetting players = bool("Players", true, "Target players.");
    public final BoolSetting hostiles = bool("Hostiles", false, "Target monsters.");
    public final BoolSetting animals = bool("Animals", false, "Target animals.");
    public final BoolSetting invisibles = bool("Invisibles", true, "Target invisible entities.");
    public final BoolSetting naked = bool("Naked", true, "Target players without armour.");
    public final ModeSetting sort = mode("Sort", "Distance", "Target priority.", "Distance", "Health", "Angle");
    public final BoolSetting onlyWeapon = bool("OnlyWeapon", false, "Only attack while holding a weapon.");
    public final BoolSetting autoWeapon = bool("AutoWeapon", false, "Switch to your best weapon before hitting.");
    public final BoolSetting pauseEating = bool("PauseOnUse", true, "Pause while eating/drinking/using.");
    public final BoolSetting pauseCrystal = bool("PauseOnCrystal", true, "Let CrystalAura act first when it has work.");
    public final BoolSetting autoWalk = bool("AutoWalk", false, "Walk toward the target (real key presses; any WASD you press takes over).");
    public final NumberSetting walkStop = num("WalkStop", 2.4, 0.5, 4, 0.1, "AutoWalk: stop this close to the target.");
    public final NumberSetting walkRange = num("WalkRange", 12, 3, 32, 1, "AutoWalk: chase targets up to this far.");
    public final BoolSetting walkJump = bool("WalkJump", true, "AutoWalk: jump over 1-block obstacles.");
    public final BoolSetting walkSprint = bool("WalkSprint", true, "AutoWalk: sprint while chasing (when legal).");

    public final BoolSetting kbDisplace = bool("KBDisplace", false, "Bend your sprint hits' knockback sideways by KBAngle (the sprint push follows your hit's yaw).");
    public final NumberSetting kbAngle = num("KBAngle", 45, -90, 90, 1, "Knockback angle off the straight line from you, + = to your right.").visibleWhen(() -> kbDisplace.get());
    public final BoolSetting kbFull = bool("KBFull", false, "Use the whole angle even when your hit no longer points at their hitbox. Vanilla / no-AC servers only (Grim flags it).").visibleWhen(() -> kbDisplace.get());
    public final BoolSetting kbLines = bool("KBLines", true, "Guide on the target: a line that always faces you, the offset line at KBAngle, and where they'll actually fly.").visibleWhen(() -> kbDisplace.get());

    /** The yaw offset actually used this tick (KBAngle, or less where the hitbox doesn't allow more). */
    private float kbApplied;

    private LivingEntity chase;

    private LivingEntity target;

    public KillAura() { super("KillAura", "Attacks entities around you.", Category.COMBAT); }

    @Override public void onDisable() { target = null; chase = null; }

    /** Where AutoWalk should walk this tick, or null. Read by MovementHooks while building input. */
    public Vec3 chasePoint() {
        if (!autoWalk.get() || chase == null || !chase.isAlive()) return null;
        double d = mc.player.distanceTo(chase);
        if (d <= walkStop.get() || d > walkRange.get()) return null;
        return chase.position();
    }

    @Override
    public void onTick() {
        target = null;
        chase = null;
        if (autoWalk.get()) {
            EntityUtil.TargetFilter wf = new EntityUtil.TargetFilter(players.get(), hostiles.get(), animals.get(), invisibles.get(), naked.get());
            List<LivingEntity> far = EntityUtil.targets(walkRange.get(), wf, sort.get());
            if (!far.isEmpty()) chase = far.get(0);
        }
        if (pauseEating.get() && mc.player.isUsingItem()) return;
        if (onlyWeapon.get() && !CombatUtil.isWeapon(mc.player.getMainHandItem())) return;
        CrystalAura ca = Prism.modules().get(CrystalAura.class);
        if (pauseCrystal.get() && ca != null && ca.isEnabled() && ca.isBusy()) return;

        EntityUtil.TargetFilter filter = new EntityUtil.TargetFilter(players.get(), hostiles.get(), animals.get(), invisibles.get(), naked.get());
        List<LivingEntity> list = EntityUtil.targets(Math.max(rotateRange.get(), attackRange() + 0.5), filter, sort.get());
        if (list.isEmpty()) return;
        target = list.get(0);
        Hud.setTarget(target);
        if (kbDisplace.get()) faceDisplaced(target); else CombatUtil.face(target, 100);

        double reach = attackRange();
        double dist = RotationUtil.distanceTo(target);
        if (dist > reach) return;
        if (!mc.player.hasLineOfSight(target) && dist > wallRange.get()) return;
        if (!Criticals.allowsAttack()) return;

        if (autoWeapon.get() && CombatUtil.cooldownReady()) {
            int w = CombatUtil.bestWeapon(false);
            if (w != -1 && Prism.guard().canSwitchSlot()) InvUtil.swap(w, false);
        }
        CombatUtil.attack(target, true);
    }

    public LivingEntity getTarget() { return target; }

    // ---- knockback displacement ------------------------------------------------------------------

    /** Aim at the target with the yaw turned by KBAngle, kept inside their hitbox unless KBFull. */
    private void faceDisplaced(LivingEntity t) {
        Vec3 eye = mc.player.getEyePosition();
        AABB box = HitboxUtil.attackBox(t);
        Vec3 c = box.getCenter();
        float[] base = RotationUtil.toward(eye, c);
        float off = kbAngle.getFloat();
        if (!kbFull.get()) {
            // the ray must still enter the box the server raytraces against: at most to its edge (minus a margin)
            double horiz = Math.max(0.5, Math.hypot(c.x - eye.x, c.z - eye.z));
            double half = Math.max(0, Math.min(box.getXsize(), box.getZsize()) / 2 - 0.06);
            float max = (float) Math.toDegrees(Math.atan2(half, horiz));
            off = Mth.clamp(off, -max, max);
        }
        kbApplied = off;
        Prism.rotations().request(base[0] + off, base[1], 100);
    }

    /** Horizontal unit direction for a yaw (where a hit with that yaw pushes). */
    private static Vec3 yawDir(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        LivingEntity t = target;
        if (!kbDisplace.get() || !kbLines.get() || t == null || !t.isAlive()) return;
        Vec3 tp = t.getPosition(delta).add(0, 0.1, 0);
        Vec3 me = mc.player.getPosition(delta);
        float awayYaw = (float) Math.toDegrees(Math.atan2(-(tp.x - me.x), tp.z - me.z));
        Vec3 away = yawDir(awayYaw);
        // main line: from inside them toward you, always facing you
        Render3D.line(tp, tp.subtract(away.scale(1.4)), 0xD0FFFFFF);
        // offset line: KBAngle off the straight line, on their far side (where you want them to go)
        Render3D.line(tp, tp.add(yawDir(awayYaw + kbAngle.getFloat()).scale(2.6)), 0xFFFF4D8A);
        // where a sprint hit actually sends them: 0.4 straight away from you + 0.5 along the hit's yaw
        double sprintPush = mc.player.isSprinting() ? 0.5 : 0;
        Vec3 kb = away.scale(0.4).add(yawDir(awayYaw + kbApplied).scale(sprintPush)).normalize();
        Render3D.line(tp.add(0, 0.05, 0), tp.add(0, 0.05, 0).add(kb.scale(2.2)), 0xB0FFC040);
    }

    /** Attack range: our setting, extended by the Reach module (Grim/NCP modes stay inside what the AC accepts). */
    private double attackRange() {
        double want = range.get();
        dev.prismglass.module.player.Reach r = Prism.modules().get(dev.prismglass.module.player.Reach.class);
        if (r != null && r.isEnabled() && !r.mode.is("Vanilla")) want = Math.max(want, r.allowedRange());
        else if (r != null && r.isEnabled()) want += r.entity.get();
        return Prism.anticheat().range(want);
    }

    @Override public String getInfo() { return target == null ? null : target.getName().getString(); }
}
