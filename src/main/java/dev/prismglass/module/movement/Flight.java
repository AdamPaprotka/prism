package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Flight.
 *
 * <p>"Grim": Grim predicts every tick of your movement from physics, and any offset beyond ~0.001 blocks a tick is a
 * violation (a setback once about one block of it piled up, or 0.1 in a single tick), so a hovering player costs 0.078
 * blocks of offset every tick and is rubber-banded within ~13 ticks. The only flight it cannot object to is
 * standing on something. So this mode drives {@link Scaffold} (real jumps, real edge sneaks, silent rotations): a block
 * is placed under your feet wherever you go, which makes WASD walk on air, Space climb (a tower when you stand, steps
 * when you move) and standing still hover. Shift lets go of the block you stand on so you drop. Needs blocks in the
 * hotbar. Tested on Grim 2.3.74: 17 blocks of air-walking, 11 blocks of tower, and a 19 x 12 diagonal climb, each in
 * 100-120 ticks with 0 alerts and 0 setbacks.
 */
public class Flight extends Module {
    public final ModeSetting mode = mode("Mode", "Vanilla", "Vanilla/Creative are [unsafe on any AC]. Grim = fly on blocks placed under your feet (needs blocks in the hotbar): WASD walks on air, Space climbs, Shift drops. Clean on Grim. Freeze = hang motionless in the air with no blocks and no movement; starts at the top of a jump (or any standstill in the air).", "Vanilla", "Creative", "Grim", "Freeze");
    public final NumberSetting speed = num("Speed", 1.0, 0.1, 10, 0.1, "Horizontal speed.").visibleWhen(() -> !mode.is("Grim"));
    public final NumberSetting vSpeed = num("VerticalSpeed", 0.6, 0.1, 5, 0.1, "Vertical speed.").visibleWhen(() -> mode.is("Vanilla"));
    public final BoolSetting antiKick = bool("AntiKick", true, "Drop slightly every 40 ticks to dodge the vanilla fly-kick.").visibleWhen(() -> mode.is("Vanilla"));
    public final BoolSetting dropOnShift = bool("DropOnShift", true, "Grim: hold Shift to stop placing blocks and let go of the edge, so you fall (land in water or use NoFall Bucket).").visibleWhen(() -> mode.is("Grim"));

    public final BoolSetting spoofGround = bool("SpoofGround", true, "Freeze: send the ground flag a player standing still sends. Without it Grim logs a GroundSpoof alert about once a second.").visibleWhen(() -> mode.is("Freeze"));

    private int ticks;

    // Freeze mode: where we hang, and the teleport count that tells us the server moved us
    private Vec3 anchor, latchVelocity;
    private int anchorTeleports;

    // Grim mode: what we changed on Scaffold, so it can be put back
    private boolean grimOn, scaffoldWasOn, drop, warned;
    private boolean savedTower, savedSafe;
    private String savedTowerMode;
    private double savedExtend;

    public Flight() { super("Flight", "Fly in survival.", Category.MOVEMENT); }

    @Override
    public void onDisable() {
        grimOff();
        freezeRelease();
        if (!mc.player.isCreative() && !mc.player.isSpectator()) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().mayfly = false;
        }
    }

    @Override
    public void onWorldJoin() {
        // switched off while no world existed: onDisable never ran, so put Scaffold back now
        if (grimOn && !isEnabled()) grimOff();
    }

    @Override
    public void onTick() {
        ticks++;
        if (mode.is("Grim")) { freezeRelease(); grimTick(); return; }
        if (grimOn) grimOff();
        if (mode.is("Freeze")) { freezeTick(); return; }
        freezeRelease();
        if (mode.is("Creative")) {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.getAbilities().setFlyingSpeed(speed.getFloat() * 0.05f);
        }
    }

    // ---- Freeze: no blocks, no movement -------------------------------------------------------------------

    /**
     * Pins the player where it is with no velocity, so the client moves nothing and sends no position packets. Grim
     * simulates one tick per position packet and a hover costs 0.078 blocks of offset a tick, so moving (even 0.1 blocks a
     * tick at constant height) is rubber-banded within a few ticks; a player that sends nothing is just lagging, and
     * lag is allowed. Two things have to be right: the ground flag of the position reminder sent every 20 ticks (SpoofGround),
     * and the moment of freezing, which must be a standstill: freezing mid-fall stops a body Grim expects to be falling
     * 1.3 blocks a tick, and that is an immediate setback. So it waits until you are in the air with almost no vertical
     * speed (the top of a jump, of a wind charge or a pearl) and latches there; the velocity it took is given back when it ends,
     * so the fall carries on exactly where Grim expects it.
     */
    private void freezeTick() {
        var p = mc.player;
        if (anchor != null && Prism.serverTeleports != anchorTeleports) anchor = null; // the server moved us: start over
        if (anchor == null) {
            Vec3 v = p.getDeltaMovement();
            if (p.onGround() || p.isInWater() || p.isFallFlying() || Math.abs(v.y) > 0.03) return; // keys work normally until then
            anchor = p.position();
            anchorTeleports = Prism.serverTeleports;
            latchVelocity = v;
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(anchor.x, anchor.y, anchor.z);
        p.fallDistance = 0;
    }

    /** Let go: hand back the velocity we took so the fall continues the trajectory the server expects. */
    private void freezeRelease() {
        if (anchor == null) return;
        if (mc.player != null && latchVelocity != null) mc.player.setDeltaMovement(latchVelocity);
        anchor = null;
        latchVelocity = null;
    }

    /** Freeze mode and pinned: movement keys are ignored (see MovementHooks). */
    public boolean frozen() { return isEnabled() && mode.is("Freeze") && anchor != null; }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (mode.is("Freeze") && anchor != null && spoofGround.get() && e.packet instanceof ServerboundMovePlayerPacket p) p.onGround = true;
    }

    // ---- Grim: fly on placed blocks ---------------------------------------------------------------------

    private void grimTick() {
        Scaffold sc = Prism.modules().get(Scaffold.class);
        if (sc == null) return;
        if (!grimOn) {
            grimOn = true;
            scaffoldWasOn = sc.isEnabled();
            savedTower = sc.tower.get();
            savedTowerMode = sc.towerMode.get();
            savedSafe = sc.safe.get();
            savedExtend = sc.extend.get();
            warned = false;
        }
        // the Grim-verified Scaffold setup, whatever the user's own Scaffold settings are
        sc.tower.set(true);
        sc.towerMode.parse("Legit");
        sc.safe.set(true);
        sc.extend.set(0.0);
        if (!sc.isEnabled()) sc.setEnabled(true);
        // the raw key: Scaffold's own edge sneak is also in keyPresses
        drop = dropOnShift.get() && mc.options.keyShift.isDown();
        sc.paused = drop;
        if (!sc.hasBlocks() && !warned && ticks % 20 == 0) {
            warned = true;
            ChatUtil.error("Flight (Grim) needs blocks in your hotbar");
        }
        if (sc.hasBlocks()) warned = false;
    }

    private void grimOff() {
        if (!grimOn) return;
        grimOn = false;
        drop = false;
        Scaffold sc = Prism.modules().get(Scaffold.class);
        if (sc == null) return;
        sc.paused = false;
        sc.tower.set(savedTower);
        sc.towerMode.parse(savedTowerMode);
        sc.safe.set(savedSafe);
        sc.extend.set(savedExtend);
        if (!scaffoldWasOn && sc.isEnabled()) sc.setEnabled(false);
    }

    /** Grim mode with Shift held: the sneak is released (see MovementHooks) and Scaffold stops placing. */
    public boolean dropping() { return grimOn && drop; }

    @Override
    public void onMove(MoveEvent e) {
        if (!mode.is("Vanilla")) return;
        double[] d = EntityUtil.directionSpeed(speed.get(), mc.player.getYRot());
        e.setHorizontal(d[0], d[1]);
        double y = 0;
        if (mc.player.input.keyPresses.jump()) y += vSpeed.get();
        if (mc.player.input.keyPresses.shift()) y -= vSpeed.get();
        if (antiKick.get() && ticks % 40 == 0 && y == 0) y = -0.04;
        e.y = y;
        mc.player.setDeltaMovement(0, 0, 0);
    }

    @Override public String getInfo() { return mode.get(); }
}
