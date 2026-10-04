package dev.prismglass.module.client;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;

/**
 * Global anticheat profile. Every combat/placing module reads its limits from here so one
 * switch adapts the whole client to the server's anticheat. Values come from reading the
 * actual check sources (GrimAC 2.x, Updated-NoCheatPlus) - see BYPASSES.md for the mapping.
 *
 * <p>Summary of what the Grim preset is built around:
 * <ul>
 *   <li>Reach/RotationPlace ray-trace from the rotation of the movement packet around the action
 *       (current, or previous tick). We only act when the <i>already sent</i> rotation hits.</li>
 *   <li>Reach limit is 3.0 (+0.0005). Hitbox is not expanded on 1.9+ clients.</li>
 *   <li>MultiPlace / MultiInteractA: one place / one entity target per tick.</li>
 *   <li>PacketOrderE: slot changes must come before attack/place/use in a tick, never after -
 *       swap-backs are deferred to the next tick by PacketGuard.</li>
 *   <li>PacketOrderO: no action packets between the movement packet and the tick-end packet.</li>
 *   <li>BadPacketsJ: use-item packets carry yaw/pitch; they are rewritten to the silent rotation.</li>
 *   <li>AimDuplicateLook: never send a rotation packet identical to the last one.</li>
 *   <li>AntiKB: offset threshold 0.001 - cancel/percent velocity always flags; JumpReset is legit.</li>
 *   <li>Timer: each flying packet adds 50ms, drift 120ms - extra look packets drain the balance.</li>
 * </ul>
 * NCP preset: reach 4.1 shrinking toward 3.2 when abused, Angle check flags average hit spacing
 * under 150ms and fast >30 degree target switches.
 */
public class AntiCheat extends Module {
    public final ModeSetting preset = mode("Preset", "Grim", "Applies a tuned profile. Edit values afterwards freely.",
        "Vanilla", "NCP", "Grim", "Vulcan", "2b2t", "Custom");
    public final NumberSetting rotationSpeed = num("RotateSpeed", 70, 5, 180, 1, "Max degrees turned per tick.");
    public final NumberSetting jitter = num("Jitter", 0.8, 0, 3, 0.1, "Random noise so rotation deltas never repeat (DuplicateRotPlace).");
    public final BoolSetting gcdFix = bool("GCDFix", true, "Snap rotation deltas to your mouse sensitivity grid (AimProcessor).");
    public final ModeSetting moveFix = mode("MoveFix", "Silent", "Correct movement for silent rotations (prediction).", "Off", "Silent", "Strict");
    public final BoolSetting strictRaycast = bool("StrictRaycast", true, "Only hit/place when the sent rotation faces the target.");
    public final ModeSetting placeRotate = mode("PlaceRotate", "Silent", "Silent = rotate with movement packet. Packet = extra look packet (drains Grim Timer).", "None", "Packet", "Silent");
    public final BoolSetting attackCooldown = bool("Cooldown", true, "Respect the 1.9+ attack cooldown.");
    public final NumberSetting minAttackDelay = num("MinAttackDelay", 0, 0, 500, 10, "Minimum ms between hits (NCP Angle flags avg < 150ms).");
    public final NumberSetting placesPerTick = num("PlacesPerTick", 1, 1, 10, 1, "Max blocks placed per tick (Grim MultiPlace = 1).");
    public final BoolSetting oneTargetPerTick = bool("OneTargetPerTick", true, "Never interact with two entities in a tick (MultiInteractA).");
    public final BoolSetting packetOrder = bool("PacketOrder", true, "Defer slot swaps/actions that would break packet order (PacketOrderE/O, BadPacketsA).");
    public final BoolSetting fixUseRotation = bool("FixUseRotation", true, "Write the silent rotation into use-item packets (BadPacketsJ).");
    public final BoolSetting humanize = bool("Humanize", true, "Human-like hit timing and wandering aim (Vulcan AutoClicker / KillAura Pattern).");
    public final NumberSetting humanizeAmount = num("HumanizeAmount", 1.0, 0.3, 2.5, 0.05, "Scales the extra hit delay (higher = more human, slower).");
    public final BoolSetting interpReach = bool("InterpReach", true, "Measure hits against the target's drawn + newest server position (the box Grim accepts). More reach on moving targets, never flags.");
    public final BoolSetting strictDirection = bool("StrictDirection", true, "Only place against faces you can see (PositionPlace).");
    public final BoolSetting airPlace = bool("AllowAirPlace", false, "Allow placing without support (flags AirLiquidPlace).");
    public final BoolSetting invStrict = bool("InvStrict", true, "Stop sprinting before inventory clicks.");
    public final NumberSetting maxRange = num("AttackRange", 2.9, 2.5, 6, 0.05, "Hard cap on entity attack range.");
    public final NumberSetting placeRange = num("PlaceRange", 4.5, 3, 6, 0.1, "Hard cap on block place/break range (to closest point).");

    private String lastPreset = "";

    public AntiCheat() {
        super("AntiCheat", "Global bypass profile used by all modules.", Category.CLIENT);
        drawn.set(false);
    }

    /**
     * After the config loaded: remember the saved preset so it is only re-applied when the user
     * actually switches presets (otherwise every restart would overwrite their tweaks).
     */
    public void markLoaded() {
        lastPreset = preset.get();
        if (isGrim()) enforceGrimSafeVelocity();
    }

    /** Called every client tick regardless of enabled state (profile is always active). */
    public void update() {
        if (preset.get().equals(lastPreset)) return;
        lastPreset = preset.get();
        switch (lastPreset) {
            case "Vanilla" -> apply(180, 0, false, "Off", false, "None", false, 0, 10, false, false, false, false, true, false, 6.0, 6.0);
            // range cap 4.0: the real limit comes from ReachBudget (NCP's dynamic reach, 4.02 shrinking to 3.22)
            // Updated-NCP (2026) predicts movement from your keys + sent yaw like Grim: silent move fix and rotations
            // in the movement packet (extra look packets are extra "moves"). Harmless on classic NCP too.
            case "NCP" -> apply(60, 0.5, true, "Silent", false, "Silent", true, 160, 2, false, false, true, true, false, true, 4.0, 4.5);
            // 2.9 not 3.0: Grim checks against its own interpolated target box, keep slack for that
            case "Grim" -> apply(70, 0.8, true, "Silent", true, "Silent", true, 0, 1, true, true, true, true, false, true, 2.9, 4.5);
            // Vulcan is closed source: built from its known behaviour (heavy aim heuristics -> smooth,
            // jittered, GCD-aligned turns; ~3.1 reach; packet-order and multi-target checks).
            case "Vulcan" -> apply(55, 1.2, true, "Silent", true, "Silent", true, 60, 1, true, true, true, true, false, true, 3.0, 4.5);
            case "2b2t" -> apply(100, 0.6, true, "Silent", true, "Silent", true, 100, 2, true, true, true, true, false, true, 3.4, 4.5);
            default -> {}
        }
        if (isGrim()) enforceGrimSafeVelocity();
    }

    /**
     * Grim predicts entity/block pushing and knockback exactly: pushing tweaks always flag, and only
     * JumpReset (legit) or GrimBudget (stays under the setback limit) make sense for knockback.
     */
    private void enforceGrimSafeVelocity() {
        var v = dev.prismglass.Prism.modules().get(dev.prismglass.module.movement.Velocity.class);
        if (v == null) return;
        v.entityPush.set(false);
        v.blockPush.set(false);
        if (!v.mode.is("JumpReset") && !v.mode.is("GrimBudget")) v.mode.parse("GrimBudget");
    }

    private void apply(double speed, double jit, boolean gcd, String mf, boolean raycast, String pr, boolean cd,
                       double minDelay, int ppt, boolean oneTarget, boolean order, boolean useRot, boolean strictDir,
                       boolean air, boolean inv, double range, double place) {
        rotationSpeed.set(speed);
        jitter.set(jit);
        gcdFix.set(gcd);
        moveFix.parse(mf);
        strictRaycast.set(raycast);
        placeRotate.parse(pr);
        attackCooldown.set(cd);
        minAttackDelay.set(minDelay);
        placesPerTick.set((double) ppt);
        oneTargetPerTick.set(oneTarget);
        packetOrder.set(order);
        fixUseRotation.set(useRot);
        strictDirection.set(strictDir);
        airPlace.set(air);
        invStrict.set(inv);
        maxRange.set(range);
        placeRange.set(place);
    }

    public boolean isGrim() { return preset.is("Grim") || preset.is("2b2t"); }

    /** Clamp a module's requested range to the profile's cap. */
    public double range(double requested) { return Math.min(requested, maxRange.get()); }
    public double placeRange(double requested) { return Math.min(requested, placeRange.get()); }

    @Override public void toggle() { /* always on */ }
    @Override public boolean isEnabled() { return true; }
}
