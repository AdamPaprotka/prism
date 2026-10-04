package dev.prismglass.manager;

import dev.prismglass.Prism;
import dev.prismglass.module.client.AntiCheat;
import dev.prismglass.util.RotationUtil;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Silent ("server-side") rotations.
 *
 * <p>Modules call {@link #request} during their tick. Once per tick the manager turns the
 * <i>server</i> rotation toward the highest-priority request, limited by the AntiCheat profile's
 * speed, with jitter and GCD snapping so the deltas look like real mouse input. The chosen
 * rotation is swapped into the player only for the duration of sendMovementPackets, so the
 * camera never moves. When requests stop, the rotation eases back to the camera before releasing,
 * avoiding the instant snap-back that rotation-heuristic checks catch.
 */
public final class RotationManager {
    private final Minecraft mc = Minecraft.getInstance();

    /** Rotation the server currently believes we have (last one sent). */
    private float serverYaw, serverPitch;
    /** Rotation chosen for this tick's movement packet. */
    private float yaw, pitch;
    private float targetYaw, targetPitch;
    private int priority = Integer.MIN_VALUE;
    private int holdTicks;
    private boolean active, returning;

    private float realYaw, realPitch;
    private boolean swapped;

    /**
     * Ask to face a rotation. Higher priority wins within a tick. The request is held for a couple of
     * ticks so modules that request every other tick don't flicker.
     */
    public void request(float yaw, float pitch, int priority) {
        if (holdTicks > 0 && priority < this.priority && !returning) return;
        this.targetYaw = yaw;
        this.targetPitch = Mth.clamp(pitch, -90f, 90f);
        this.priority = priority;
        this.holdTicks = 2;
        this.active = true;
        this.returning = false;
    }

    public void request(Vec3 point, int priority) {
        float[] r = RotationUtil.toward(point);
        request(r[0], r[1], priority);
    }

    /** Runs once per tick after modules ticked, before input and movement. */
    public void update() {
        if (mc.player == null) { active = false; return; }
        if (!active) {
            yaw = serverYaw = mc.player.getYRot();
            pitch = serverPitch = mc.player.getXRot();
            return;
        }

        // an item was used this tick with the rotation the server already has: keep it for this tick's movement
        // packet too (Grim BadPacketsJ: use-item rotation must equal the tick's rotation)
        if (Prism.guard().hasUsed()) return;
        if (holdTicks > 0) holdTicks--;
        else if (!returning) { returning = true; priority = Integer.MIN_VALUE; }

        float goalYaw = returning ? mc.player.getYRot() : targetYaw;
        float goalPitch = returning ? mc.player.getXRot() : targetPitch;

        AntiCheat ac = Prism.anticheat();
        float speed = ac.rotationSpeed.getFloat();
        float dYaw = Mth.wrapDegrees(goalYaw - serverYaw);
        float dPitch = goalPitch - serverPitch;

        // Scale both axes together so the turn follows a straight line, like a hand would.
        float len = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        if (ac.humanize.get() && !returning) {
            // ease-out: cover a varying fraction of the remaining angle, capped by a varying max speed.
            // Real flicks decelerate into the target; a constant-speed sweep is a pattern.
            ThreadLocalRandom r = ThreadLocalRandom.current();
            float step = Math.min(speed * (0.6f + 0.4f * r.nextFloat()), len * (0.45f + 0.35f * r.nextFloat()) + 0.4f);
            if (len > step) {
                dYaw = dYaw / len * step;
                dPitch = dPitch / len * step;
            }
        } else if (len > speed) {
            dYaw = dYaw / len * speed;
            dPitch = dPitch / len * speed;
        }

        float jitter = ac.jitter.getFloat();
        if (jitter > 0 && len > 0.5f && !returning) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            dYaw += (float) (r.nextGaussian() * jitter * 0.5);
            dPitch += (float) (r.nextGaussian() * jitter * 0.35);
        }

        if (ac.gcdFix.get()) {
            float gcd = RotationUtil.gcd();
            dYaw = Math.round(dYaw / gcd) * gcd;
            dPitch = Math.round(dPitch / gcd) * gcd;
        }

        yaw = serverYaw + dYaw;
        pitch = Mth.clamp(serverPitch + dPitch, -90f, 90f);

        if (returning && RotationUtil.angleDiff(yaw, pitch, mc.player.getYRot(), mc.player.getXRot()) < 1.5f) {
            active = false;
            returning = false;
            // the sent yaw may sit a multiple of 360 away from the camera: move the camera by that (looks identical)
            // so the next normal packet doesn't snap 360 degrees
            float turns = Math.round((yaw - mc.player.getYRot()) / 360f) * 360f;
            if (turns != 0) {
                mc.player.setYRot(mc.player.getYRot() + turns);
                mc.player.yRotO += turns;
            }
        }
    }

    // ---- packet swap (called from ClientPlayerEntity mixin) --------------------------------

    public void preSend() {
        if (!active || mc.player == null) return;
        realYaw = mc.player.getYRot();
        realPitch = mc.player.getXRot();
        // Send yaw continuous with what the server last got (it already is: yaw = serverYaw + delta). Re-wrapping
        // it around the camera flips +-180 when looking behind you = a 360 degree snap (Grim AimModulo360).
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        swapped = true;
    }

    public void postSend() {
        if (!swapped) return;
        mc.player.setYRot(realYaw);
        mc.player.setXRot(realPitch);
        swapped = false;
    }

    /** Track what the server actually received. */
    public void onSend(ServerboundMovePlayerPacket packet) {
        if (packet.hasRotation()) {
            serverYaw = packet.getYRot(serverYaw);
            serverPitch = packet.getXRot(serverPitch);
        }
    }

    // ---- queries --------------------------------------------------------------------------

    public boolean isActive() { return active; }
    /** Yaw used for movement this tick (server yaw when rotating, camera otherwise). */
    public float getMoveYaw() { return active ? yaw : mc.player.getYRot(); }
    public float getMovePitch() { return active ? pitch : mc.player.getXRot(); }

    /**
     * Elytra flight and firework boosts follow where the server thinks we look. While a silent rotation is active
     * (and the move fix is on) our own physics must use it too, or Grim's prediction disagrees.
     */
    public boolean silentLookFor(net.minecraft.world.entity.Entity e) {
        return active && e == mc.player && !Prism.anticheat().moveFix.is("Off");
    }

    public net.minecraft.world.phys.Vec3 silentLook() { return dev.prismglass.util.RotationUtil.direction(yaw, pitch); }
    public float getServerYaw() { return serverYaw; }
    public float getServerPitch() { return serverPitch; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }

    /**
     * Does the rotation the server last received point into the box? Only the direction is checked here: callers
     * already keep the box within range by its closest point (how Grim's FarPlace and vanilla measure block reach),
     * and the point we aim at (a face centre) can lie a little beyond that, so the ray gets some extra length.
     */
    public boolean isFacing(AABB box, double range) {
        if (!Prism.anticheat().strictRaycast.get()) return true;
        Vec3 eye = mc.player.getEyePosition();
        if (box.contains(eye)) return true;
        // never longer than the server's own block reach: Grim's RotationPlace casts exactly that far
        Vec3 end = eye.add(RotationUtil.direction(serverYaw, serverPitch).scale(Math.min(range + 2.0, serverReach())));
        return box.clip(eye, end).isPresent();
    }

    /**
     * Distance from the eye to where the rotation the server already has enters the box, or -1 if
     * that ray misses. This is exactly how Grim's Reach check measures a hit, so comparing this to
     * the range (instead of the closest point of the box) keeps attacks inside its limit even while
     * the silent rotation is still catching up with a moving target.
     */
    public double rayDistance(AABB box, double maxRange) {
        Vec3 eye = mc.player.getEyePosition();
        if (box.contains(eye)) return 0;
        Vec3 end = eye.add(RotationUtil.direction(serverYaw, serverPitch).scale(maxRange + 3));
        return box.clip(eye, end).map(eye::distanceTo).orElse(-1.0);
    }

    /** The block reach the server uses (the attribute itself, not our Reach module's client value). */
    public double serverReach() {
        return mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
    }

    /** Does the server rotation look at this block (for strict placing)? */
    public boolean isFacingBlock(BlockPos pos, double range) {
        if (!Prism.anticheat().strictRaycast.get()) return true;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(RotationUtil.direction(serverYaw, serverPitch).scale(range + 2.0));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** Send a rotation immediately (used by "Packet" place-rotate mode). */
    public void sendLook(float yaw, float pitch) {
        if (Prism.anticheat().gcdFix.get()) {
            float gcd = RotationUtil.gcd();
            yaw = serverYaw + Math.round(Mth.wrapDegrees(yaw - serverYaw) / gcd) * gcd;
            pitch = serverPitch + Math.round((pitch - serverPitch) / gcd) * gcd;
        }
        // Grim AimDuplicateLook: an unchanged rotation packet is an instant flag.
        if (yaw == serverYaw && pitch == serverPitch) return;
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch,
            mc.player.onGround(), mc.player.horizontalCollision));
    }
}
