package dev.prismglass.manager;

import net.minecraft.world.phys.Vec2;
import dev.prismglass.Prism;
import dev.prismglass.module.combat.KillAura;
import dev.prismglass.module.movement.AutoJump;
import dev.prismglass.module.movement.AutoWalk;
import dev.prismglass.module.movement.Parkour;
import dev.prismglass.module.movement.Sneak;
import dev.prismglass.module.movement.Velocity;
import dev.prismglass.module.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

/**
 * Rewrites the keyboard input after vanilla computed it, before the player moves.
 *
 * <p><b>MoveFix (Silent):</b> Grim predicts our velocity from the yaw in our movement packet.
 * While silently rotating that yaw differs from the camera, so we re-pick WASD relative to the
 * server yaw so the resulting direction is as close as possible to what the player intended,
 * and {@code Entity#updateVelocity} is redirected to use the server yaw. The PlayerInput flags
 * (sent to the server on 1.21.2+) are rewritten to match so nothing disagrees.
 */
public final class MovementHooks {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final int[][] COMBOS = {
        {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    /** 2 = release jump this tick, 1 = press jump this tick (restart gliding), 0 = idle. */
    private static int glidePhase;

    private MovementHooks() {}

    /**
     * Restart elytra gliding the way vanilla does: jump released for one tick, then pressed. Vanilla then
     * sends START_FALL_FLYING itself, followed by the jump input - the exact order Grim's ElytraB expects.
     */
    public static void requestGlide() { if (glidePhase == 0) glidePhase = 2; }

    public static boolean glidePending() { return glidePhase != 0; }

    private static boolean jumpRequest;

    /**
     * Walk in ClickGUI: screens release every key mapping, so read the movement binds' physical keys directly.
     * The ClickGUI isn't a container, so the server sees ordinary movement.
     */
    private static void guiWalk(ClientInput input) {
        if (!(mc.screen instanceof dev.prismglass.gui.ClickGuiScreen gui) || gui.capturesKeys()) return;
        var theme = Prism.modules().get(dev.prismglass.module.client.ClickGui.class);
        if (theme == null || !theme.walk.get()) return;
        var o = mc.options;
        Input keys = new Input(held(o.keyUp), held(o.keyDown), held(o.keyLeft), held(o.keyRight), held(o.keyJump), held(o.keyShift), held(o.keySprint));
        input.keyPresses = keys;
        float forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
        float left = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
        input.moveVector = new Vec2(left, forward).normalized();
        if (keys.sprint() && forward > 0 && !mc.player.isSprinting()) mc.player.setSprinting(true);
    }

    private static boolean held(net.minecraft.client.KeyMapping mapping) {
        var key = mapping.key;
        if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) return false;
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), key.getValue());
    }

    /** Press jump for one tick through real input (Criticals Jump mode). */
    public static void requestJump() { jumpRequest = true; }

    public static void afterInputTick(ClientInput input) {
        if (mc.player == null) return;
        guiWalk(input);

        Freecam freecam = Prism.modules().get(Freecam.class);
        if (freecam != null && freecam.isEnabled()) {
            freecam.captureInput(input);
            input.moveVector = Vec2.ZERO;
            input.keyPresses = new Input(false, false, false, false, false, false, false);
            return;
        }

        // Flight (Freeze): hanging motionless, keys must not nudge us (any step is a Grim Simulation offset)
        dev.prismglass.module.movement.Flight frozen = Prism.modules().get(dev.prismglass.module.movement.Flight.class);
        if (frozen != null && frozen.frozen()) {
            input.moveVector = Vec2.ZERO;
            input.keyPresses = new Input(false, false, false, false, false, false, false);
            return;
        }

        AutoWalk walk = Prism.modules().get(AutoWalk.class);
        if (walk != null && walk.isEnabled() && !walk.behind()) {
            Input p = input.keyPresses;
            input.keyPresses = new Input(true, false, p.left(), p.right(), p.jump(), p.shift(), p.sprint());
            input.moveVector = vector(input.keyPresses);
        }

        var highway = Prism.modules().get(dev.prismglass.module.world.HighwayBuilder.class);
        if (highway != null && highway.wantsForward()) {
            Input p = input.keyPresses;
            float strafe = highway.wantsStrafe();
            input.keyPresses = new Input(true, false, p.left() || strafe > 0, p.right() || strafe < 0, p.jump(), p.shift(), p.sprint());
            input.moveVector = vector(input.keyPresses);
        }

        var stairs = Prism.modules().get(dev.prismglass.module.world.Staircase.class);
        if (stairs != null && (stairs.wantsForward() || stairs.wantsBack())) {
            Input p = input.keyPresses;
            input.keyPresses = new Input(stairs.wantsForward(), stairs.wantsBack(), p.left(), p.right(), p.jump() || stairs.wantsJump(), p.shift() || stairs.wantsSneak(), p.sprint() && !stairs.wantsSneak());
            input.moveVector = vector(input.keyPresses);
        }

        applyLegitInputs(input);
        if (Prism.invGuard().holdingStill()) {
            // an inventory click is queued: stand still for it (Grim MultiActionsC)
            input.moveVector = Vec2.ZERO;
            Input p = input.keyPresses;
            input.keyPresses = new Input(false, false, false, false, p.jump(), p.shift(), false);
            mc.player.setSprinting(false);
            return;
        }
        if (applyChase(input)) return; // chase already picks keys relative to the movement yaw
        applyMoveFix(input);
    }

    /**
     * WalkGoal steering (KillAura AutoWalk, AutoWalk Behind, Follow...): press the WASD combination that moves us
     * closest to the goal, relative to the yaw our movement actually uses (server yaw while silently rotating), so
     * it is plain legit input to Grim's prediction. Combinations that step into a hazard or a wall are skipped.
     * Manual input always wins, except plain W which means "go".
     */
    private static boolean applyChase(ClientInput input) {
        WalkGoal goal = null;
        net.minecraft.world.phys.Vec3 to = null;
        // AutoWalk is the explicit "walk for me" switch: it goes first, then any other module that wants to walk
        AutoWalk walk = Prism.modules().get(AutoWalk.class);
        if (walk != null && walk.isEnabled() && (to = walk.walkPoint()) != null) goal = walk;
        if (to == null) {
            for (dev.prismglass.module.Module m : Prism.modules().all()) {
                if (m == walk || !m.isEnabled() || !(m instanceof WalkGoal g)) continue;
                if ((to = g.walkPoint()) != null) { goal = g; break; }
            }
        }
        if (to == null) {
            // AutoMace: after a wind charge launch, drift over the target so the smash lands in reach
            var mace = Prism.modules().get(dev.prismglass.module.combat.AutoMace.class);
            to = mace != null && mace.isEnabled() ? mace.airChasePoint() : null;
        }
        if (to == null) return false;
        // manual steering wins, except plain W which means "go": then we steer toward the target
        boolean onlyForward = input.getMoveVector().y > 0 && input.getMoveVector().x == 0;
        if ((input.getMoveVector().y != 0 || input.getMoveVector().x != 0) && !onlyForward) return false;

        var pl = mc.player;
        Input p = input.keyPresses;
        double dx = to.x - pl.getX(), dz = to.z - pl.getZ();
        if (goal != null && dx * dx + dz * dz <= goal.walkStop() * goal.walkStop()) {
            standStill(input);
            return true;
        }

        RotationManager rot = Prism.rotations();
        float moveYaw = rot.isActive() && !Prism.anticheat().moveFix.is("Off") ? rot.getMoveYaw() : pl.getYRot();
        double want = Math.toDegrees(Math.atan2(dz, dx));
        boolean avoid = goal != null && goal.walkAvoidHazards();
        // the combinations by how close they go to where we want, the first safe one within 100 degrees wins
        int bestF = 0, bestS = 0;
        boolean found = false;
        boolean[] used = new boolean[COMBOS.length];
        for (int n = 0; n < COMBOS.length && !found; n++) {
            int pick = -1;
            double pickDiff = Double.MAX_VALUE;
            for (int i = 0; i < COMBOS.length; i++) {
                if (used[i]) continue;
                double diff = Math.abs(Mth.wrapDegrees(angleOf(COMBOS[i][0], COMBOS[i][1], moveYaw) - want));
                if (diff < pickDiff) { pickDiff = diff; pick = i; }
            }
            used[pick] = true;
            if (pickDiff > 100) break;
            if (avoid && !safeStep(angleOf(COMBOS[pick][0], COMBOS[pick][1], moveYaw))) continue;
            bestF = COMBOS[pick][0];
            bestS = COMBOS[pick][1];
            found = true;
        }
        if (!found) {
            if (goal != null) goal.walkBlocked();
            standStill(input);
            return true;
        }
        input.moveVector = new Vec2(bestS, bestF).normalized();
        boolean jump = p.jump() || (goal != null && goal.walkJump() && pl.horizontalCollision && pl.onGround());
        input.keyPresses = new Input(bestF > 0, bestF < 0, bestS > 0, bestS < 0, jump, p.shift(), p.sprint());

        // sprint only where vanilla allows it (forward pressed + every SprintA-G condition)
        // AutoMace (goal == null) steers in the air: never force sprint there, it changes air acceleration (Grim Simulation)
        if (goal != null && goal.walkSprint() && bestF > 0 && !pl.isShiftKeyDown() && !pl.isUsingItem() && !pl.horizontalCollision
            && pl.getFoodData().getFoodLevel() > 6 && !pl.isInWater() && !pl.isFallFlying()
            && !pl.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
            pl.setSprinting(true);
        }
        return true;
    }

    private static void standStill(ClientInput input) {
        Input p = input.keyPresses;
        input.moveVector = Vec2.ZERO;
        input.keyPresses = new Input(false, false, false, false, p.jump(), p.shift(), false);
    }

    /** Sample points along a step: half a block and a block and a quarter ahead. */
    private static final double[] STEP_SAMPLES = {0.5, 1.25};

    /**
     * Whether walking along {@code headingDeg} (atan2(z, x) degrees) for about a block is safe: no wall two blocks
     * high in the way, nothing that burns or pricks at feet/legs, and ground (or water) at most 3 blocks down.
     */
    private static boolean safeStep(double headingDeg) {
        var pl = mc.player;
        var level = mc.level;
        double rad = Math.toRadians(headingDeg);
        double cx = Math.cos(rad), cz = Math.sin(rad);
        int feet = Mth.floor(pl.getY() + 0.01);
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (double d : STEP_SAMPLES) {
            int bx = Mth.floor(pl.getX() + cx * d), bz = Mth.floor(pl.getZ() + cz * d);
            boolean feetSolid = solid(pos.set(bx, feet, bz));
            if (feetSolid && (solid(pos.set(bx, feet + 1, bz)) || solid(pos.set(bx, feet + 2, bz)))) return false; // wall or no headroom
            if (hurts(pos.set(bx, feet, bz)) || hurts(pos.set(bx, feet + 1, bz))) return false;
            if (feetSolid) continue; // a 1-block step up: jump handles it
            boolean ground = false;
            for (int y = feet - 1; y >= feet - 4; y--) {
                var state = level.getBlockState(pos.set(bx, y, bz));
                if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || hurts(pos)) return false;
                if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER) || !state.getCollisionShape(level, pos).isEmpty()) {
                    // magma and campfires burn when you stand on them
                    if (state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) || state.is(net.minecraft.tags.BlockTags.CAMPFIRES)) return false;
                    ground = true; // at most a 3-block drop: no fall damage
                    break;
                }
            }
            if (!ground) return false;
        }
        return true;
    }

    private static boolean solid(net.minecraft.core.BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    /** Blocks that damage, trap or slow you on contact. */
    private static boolean hurts(net.minecraft.core.BlockPos pos) {
        var s = mc.level.getBlockState(pos);
        if (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
        return s.is(net.minecraft.tags.BlockTags.FIRE) || s.is(net.minecraft.tags.BlockTags.CAMPFIRES)
            || s.is(net.minecraft.world.level.block.Blocks.CACTUS) || s.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)
            || s.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE) || s.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
            || s.is(net.minecraft.world.level.block.Blocks.COBWEB);
    }

    /** Modules that act through real key presses - indistinguishable from a human pressing them. */
    private static void applyLegitInputs(ClientInput input) {
        Input p = input.keyPresses;
        boolean jump = p.jump(), sneak = p.shift();

        Velocity velocity = Prism.modules().get(Velocity.class);
        if (velocity != null && velocity.isEnabled() && velocity.consumeJumpReset()) jump = true;

        Parkour parkour = Prism.modules().get(Parkour.class);
        if (parkour != null && parkour.isEnabled() && parkour.wantsJump()) jump = true;

        AutoJump autoJump = Prism.modules().get(AutoJump.class);
        if (autoJump != null && autoJump.isEnabled() && mc.player.onGround()
            && (!autoJump.onlyMoving.get() || input.getMoveVector().y != 0 || input.getMoveVector().x != 0)) jump = true;

        Sneak sneakMod = Prism.modules().get(Sneak.class);
        if (sneakMod != null && sneakMod.isEnabled()) sneak = true;
        dev.prismglass.module.movement.Scaffold scaffold = Prism.modules().get(dev.prismglass.module.movement.Scaffold.class);
        if (scaffold != null && scaffold.wantsSneak()) sneak = true;
        dev.prismglass.module.movement.Flight flight = Prism.modules().get(dev.prismglass.module.movement.Flight.class);
        if (flight != null && flight.dropping()) sneak = false; // Flight (Grim): Shift means "let go of the block", not "sneak"

        if (jumpRequest) { jump = true; jumpRequest = false; }
        if (glidePhase == 2) { jump = false; glidePhase = 1; }
        else if (glidePhase == 1) { jump = true; glidePhase = 0; }

        if (jump != p.jump() || sneak != p.shift()) {
            input.keyPresses = new Input(p.forward(), p.backward(), p.left(), p.right(), jump, sneak, p.sprint());
        }
    }

    /** The move vector vanilla's KeyboardInput derives from these keys. */
    private static Vec2 vector(Input k) {
        return new Vec2((k.left() ? 1 : 0) - (k.right() ? 1 : 0), (k.forward() ? 1 : 0) - (k.backward() ? 1 : 0)).normalized();
    }

    private static void applyMoveFix(ClientInput input) {
        RotationManager rot = Prism.rotations();
        if (!rot.isActive() || !Prism.anticheat().moveFix.is("Silent")) return;
        float f = input.getMoveVector().y, s = input.getMoveVector().x;
        if (f == 0 && s == 0) return;

        float real = mc.player.getYRot();
        float server = rot.getMoveYaw();
        // world-space direction the player wants (same maths as movementInputToVelocity)
        double wantAngle = angleOf(f, s, real);

        int bestF = 0, bestS = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int[] c : COMBOS) {
            double diff = Math.abs(Mth.wrapDegrees(angleOf(c[0], c[1], server) - wantAngle));
            if (diff < bestDiff) { bestDiff = diff; bestF = c[0]; bestS = c[1]; }
        }

        // exactly what KeyboardInput makes from these keys: a *normalized* vector. An unnormalized diagonal is the
        // same at full speed (clamped) but faster while sneaking/using an item = Grim Simulation flags + setbacks
        input.moveVector = new Vec2(bestS, bestF).normalized();
        Input p = input.keyPresses;
        input.keyPresses = new Input(bestF > 0, bestF < 0, bestS > 0, bestS < 0, p.jump(), p.shift(), p.sprint());
    }

    /** Heading in degrees of an input pair at a yaw. */
    private static double angleOf(float forward, float strafe, float yaw) {
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double x = strafe * cos - forward * sin;
        double z = forward * cos + strafe * sin;
        return Math.toDegrees(Math.atan2(z, x));
    }

    /** Yaw that vanilla velocity maths should use for the local player. */
    public static float velocityYaw(float original) {
        RotationManager rot = Prism.rotations();
        if (rot.isActive() && !Prism.anticheat().moveFix.is("Off")) return rot.getMoveYaw();
        return original;
    }
}
