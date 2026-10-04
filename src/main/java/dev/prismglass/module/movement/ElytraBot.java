package dev.prismglass.module.movement;

import dev.prismglass.manager.MovementHooks;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.InvUtil;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra autopilot to coordinates ({@code .elytrabot <x> <z>}): takes off, climbs to the cruise height, flies there
 * with firework boosts when slow, pulls up for anything ahead, then glides down and lands.
 *
 * <p>Elytra physics follows where you look, so it steers your real view (turned smoothly, never snapped); a silent
 * rotation would make the server simulate a different flight. Rocket slot swaps go back the next tick.
 */
public class ElytraBot extends Module {
    public final NumberSetting targetX = num("TargetX", 0, -30_000_000, 30_000_000, 1, "Destination X (or .elytrabot <x> <z>).");
    public final NumberSetting targetZ = num("TargetZ", 0, -30_000_000, 30_000_000, 1, "Destination Z.");
    public final NumberSetting cruiseY = num("CruiseY", 200, 64, 320, 1, "Height to fly at (above terrain and builds).");
    public final NumberSetting minSpeed = num("MinSpeed", 1.4, 0.4, 3, 0.05, "Use a rocket below this speed (blocks/tick).");
    public final NumberSetting rocketGap = num("RocketGap", 30, 10, 80, 1, "Ticks at least between rockets.");
    public final NumberSetting turnSpeed = num("TurnSpeed", 8, 2, 30, 1, "Max degrees your view turns per tick.");
    public final BoolSetting land = bool("Land", true, "Glide down and land at the destination (off: circle above it).");

    private enum State { TAKEOFF, CLIMB, CRUISE, DESCEND }

    private State state = State.TAKEOFF;
    private int ticks, lastRocket = -1000, restoreSlot = -1, stuckTicks;
    private double startDist;

    public ElytraBot() { super("ElytraBot", "Elytra autopilot to coordinates (.elytrabot <x> <z>).", Category.MOVEMENT); }

    public void goTo(double x, double z) {
        targetX.set(x);
        targetZ.set(z);
        if (!isEnabled()) setEnabled(true); else onEnable();
    }

    @Override
    public void onEnable() {
        state = State.TAKEOFF;
        ticks = 0;
        stuckTicks = 0;
        if (mc.player != null) startDist = horizontalDist();
        // other flight modules rewrite movement and would fight the autopilot
        for (Class<? extends Module> c : List.of(Flight.class, ElytraFly.class, PacketFly.class, BoatFly.class)) {
            Module m = dev.prismglass.Prism.modules().get(c);
            if (m != null && m.isEnabled()) {
                m.setEnabled(false);
                ChatUtil.info("ElytraBot: turned off " + m.getName());
            }
        }
        if (mc.player != null && !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            ChatUtil.error("ElytraBot: wear an elytra first");
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        if (restoreSlot != -1 && mc.player != null) InvUtil.restore(restoreSlot);
        restoreSlot = -1;
    }

    private double horizontalDist() {
        return Math.hypot(targetX.get() - mc.player.getX(), targetZ.get() - mc.player.getZ());
    }

    @Override
    public void onTick() {
        var p = mc.player;
        ticks++;
        if (restoreSlot != -1) { InvUtil.restore(restoreSlot); restoreSlot = -1; }
        if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) { done("elytra gone"); return; }

        double dist = horizontalDist();
        if (land.get() && dist < Math.max(120, (mc.player.getY() - groundHeight()) * 3) && state != State.TAKEOFF) state = State.DESCEND;

        switch (state) {
            case TAKEOFF -> takeoff();
            case CLIMB -> {
                fly(-35, true);
                if (p.getY() >= cruiseY.get() - 5) state = State.CRUISE;
            }
            case CRUISE -> cruise();
            case DESCEND -> descend(dist);
        }
    }

    private void takeoff() {
        var p = mc.player;
        if (p.isFallFlying()) { state = State.CLIMB; rocket(); return; }
        if (p.onGround()) { MovementHooks.requestJump(); return; }
        // in the air: open the elytra once falling (jump release -> press, Grim ElytraB-safe)
        if (p.getDeltaMovement().y < -0.04 && !MovementHooks.glidePending()) MovementHooks.requestGlide();
    }

    private void cruise() {
        var p = mc.player;
        if (!p.isFallFlying()) { state = State.TAKEOFF; return; }
        double err = cruiseY.get() - p.getY();
        float pitch = (float) Mth.clamp(-err * 1.5, -25, 12);
        // something ahead within ~1.5 s at this speed: pull up hard
        Vec3 vel = p.getDeltaMovement();
        Vec3 ahead = p.position().add(vel.x * 30, Math.min(0, vel.y * 30) - 1, vel.z * 30);
        boolean blocked = mc.level.clip(new ClipContext(p.position(), ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p)).getType() != HitResult.Type.MISS;
        if (blocked) pitch = -40;
        fly(pitch, blocked || err > 10);
    }

    /**
     * Landing without damage: elytra hurt you for hitting something sideways fast, or touching down while sinking
     * faster than 0.5 b/t. So: shallow glide slope in (no rockets), circle down if still high over the target, level
     * out under 12 blocks and flare under 4, pulling up for any terrain ahead on the way.
     */
    private void descend(double dist) {
        var p = mc.player;
        if (p.onGround()) { done("arrived"); return; }
        if (!p.isFallFlying()) {
            // fell out of the glide on the way down: open it again unless we're about to land anyway
            if (p.getDeltaMovement().y < -0.04 && groundHeight() < p.getY() - 4 && !MovementHooks.glidePending()) MovementHooks.requestGlide();
            return;
        }
        double above = p.getY() - groundHeight();
        float pitch, yawOffset = 0;
        if (dist > 30) {
            pitch = (float) Mth.clamp(Math.toDegrees(Math.atan2(above - 10, dist - 20)), 0, 22);   // glide slope in
        } else if (above > 12) {
            yawOffset = 75;                                                                        // orbit down
            pitch = 20;
        } else {
            pitch = above < 4 ? -8 : 2;                                                            // level out, flare
        }
        Vec3 vel = p.getDeltaMovement();
        Vec3 ahead = p.position().add(vel.x * 15, vel.y * 15, vel.z * 15);
        if (mc.level.clip(new ClipContext(p.position(), ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p)).getType() != HitResult.Type.MISS
            && above > 3) pitch = -25;                                                             // terrain ahead: up
        steer(pitch, yawOffset);
    }

    private double groundHeight() {
        int x = Mth.floor(mc.player.getX()), z = Mth.floor(mc.player.getZ());
        return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /** Turn the view toward the target (yaw) and to {@code pitch}, rocketing when slow or asked to. */
    private void fly(float pitch, boolean wantBoost) {
        var p = mc.player;
        steer(pitch, 0);
        double speed = p.getDeltaMovement().length();
        if (p.isFallFlying() && (wantBoost || speed < minSpeed.get())) rocket();
    }

    /** Smoothly turn toward the target (plus an offset, for orbiting) and to the pitch; no rockets. */
    private void steer(float pitch, float yawOffset) {
        var p = mc.player;
        double dx = targetX.get() - p.getX(), dz = targetZ.get() - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + yawOffset;
        float max = turnSpeed.getFloat();
        float dYaw = Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -max, max);
        float dPitch = Mth.clamp(pitch - p.getXRot(), -max, max);
        p.setYRot(p.getYRot() + dYaw);
        p.setXRot(Mth.clamp(p.getXRot() + dPitch, -90, 90));
        double speed = p.getDeltaMovement().length();
        if (speed < 0.05 && !p.onGround()) { if (++stuckTicks > 100) done("stuck"); } else stuckTicks = 0;
    }

    private void rocket() {
        var p = mc.player;
        if (ticks - lastRocket < rocketGap.getInt() || !p.isFallFlying()) return;
        InteractionHand hand;
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) hand = InteractionHand.OFF_HAND;
        else if (p.getMainHandItem().is(Items.FIREWORK_ROCKET)) hand = InteractionHand.MAIN_HAND;
        else {
            int slot = InvUtil.findHotbar(Items.FIREWORK_ROCKET);
            if (slot == -1) return;
            restoreSlot = p.getInventory().getSelectedSlot();
            InvUtil.swap(slot, false);
            hand = InteractionHand.MAIN_HAND;
        }
        mc.gameMode.useItem(p, hand);
        lastRocket = ticks;
    }

    private void done(String why) {
        ChatUtil.good("ElytraBot: " + why + String.format(" (%.0f, %.0f)", mc.player.getX(), mc.player.getZ()));
        setEnabled(false);
    }

    /** How many rockets are left (hotbar + offhand). */
    private int rockets() {
        int n = mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET) ? mc.player.getOffhandItem().getCount() : 0;
        for (int i = 0; i < 9; i++) {
            var s = mc.player.getInventory().getItem(i);
            if (s.is(Items.FIREWORK_ROCKET)) n += s.getCount();
        }
        return n;
    }

    @Override
    public String getInfo() {
        if (mc.player == null) return null;
        return state.name().charAt(0) + state.name().substring(1).toLowerCase() + " " + Math.round(horizontalDist()) + "m " + rockets() + "r";
    }

    /** Dev/HUD: current phase. */
    public String state() { return state.name(); }

    @SuppressWarnings("unused")
    private static boolean gliding(LivingEntity e) { return e.isFallFlying(); }
}
