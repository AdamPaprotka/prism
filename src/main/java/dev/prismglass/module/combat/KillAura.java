package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * Attacks nearby targets. Rotation is silent through the RotationManager; a hit is only sent when the
 * rotation the server already has intersects the target hitbox inside the AntiCheat range
 * (Grim Reach/Hitbox), respecting cooldown, min-delay and one-target-per-tick.
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
        CombatUtil.face(target, 100);

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
