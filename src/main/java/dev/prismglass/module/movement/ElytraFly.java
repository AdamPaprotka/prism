package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra control.
 *
 * <p>"Grim": Grim simulates elytra flight exactly from where you look, so this only ever steers the look and uses
 * real rockets. The look is a silent rotation (your camera stays free) and our own elytra/firework physics use it
 * too (LivingEntityMixin, FireworkRocketEntityMixin), so client and server fly the same flight. Yaw follows your
 * camera; pitch is ours: Space climbs, Shift dives, neither holds the height you had; rockets when slow.
 * "Control"/"Boost" rewrite your motion directly [Grim-unsafe].
 */
public class ElytraFly extends Module {
    public final ModeSetting mode = mode("Mode", "Grim", "Grim = steers only your (silent) look + real rockets, holds height (Grim-safe). Control = no gravity, direct steering. Boost = accelerate along look. [Control/Boost Grim-unsafe]", "Grim", "Bounce", "Control", "Boost");
    public final NumberSetting speed = num("Speed", 1.8, 0.1, 5, 0.05, "Control/Boost: horizontal speed.").visibleWhen(() -> !mode.is("Grim"));
    public final NumberSetting vSpeed = num("VerticalSpeed", 1.0, 0.1, 3, 0.05, "Control: up/down speed.").visibleWhen(() -> mode.is("Control"));
    public final BoolSetting holdHeight = bool("HoldHeight", true, "Grim: keep your height when you're not climbing or diving.").visibleWhen(() -> mode.is("Grim"));
    public final NumberSetting climbPitch = num("ClimbPitch", 35, 10, 80, 1, "Grim: how steep Space climbs.").visibleWhen(() -> mode.is("Grim"));
    public final NumberSetting divePitch = num("DivePitch", 40, 10, 90, 1, "Grim: how steep Shift dives.").visibleWhen(() -> mode.is("Grim"));
    public final BoolSetting autoRocket = bool("AutoRocket", true, "Grim: use a firework when you get slow.").visibleWhen(() -> mode.is("Grim"));
    public final NumberSetting minSpeed = num("MinSpeed", 1.2, 0.3, 3, 0.05, "Grim: rocket below this speed (blocks/tick).").visibleWhen(() -> mode.is("Grim") && autoRocket.get());
    public final NumberSetting rocketGap = num("RocketGap", 30, 10, 100, 1, "Grim: ticks at least between rockets.").visibleWhen(() -> mode.is("Grim") && autoRocket.get());
    public final NumberSetting bouncePitch = num("BouncePitch", 75, 0, 90, 1, "Bounce: the pitch you fly at (tune this: higher = lower, faster bounces).").visibleWhen(() -> mode.is("Bounce"));
    public final BoolSetting autoStart = bool("AutoStart", true, "Open the elytra automatically when falling.");

    private double holdY = Double.NaN;
    private int ticks, lastRocket = -1000, restoreSlot = -1;

    public ElytraFly() { super("ElytraFly", "Better elytra flight (Grim mode is Grim-safe).", Category.MOVEMENT); }

    @Override
    public void onEnable() { holdY = Double.NaN; }

    @Override
    public void onDisable() {
        if (restoreSlot != -1 && mc.player != null) InvUtil.restore(restoreSlot);
        restoreSlot = -1;
    }

    @Override
    public void onTick() {
        var p = mc.player;
        ticks++;
        if (restoreSlot != -1) { InvUtil.restore(restoreSlot); restoreSlot = -1; }
        if (autoStart.get() && !mode.is("Bounce") && !p.isFallFlying() && !p.onGround() && p.getDeltaMovement().y < -0.1
            && p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && !p.getAbilities().flying
            && !p.input.keyPresses.jump()) {
            // jump release -> press, so vanilla sends START_FALL_FLYING itself (Grim ElytraB-safe)
            dev.prismglass.manager.MovementHooks.requestGlide();
        }
        if (mode.is("Grim")) grim();
        else if (mode.is("Bounce")) bounce();
    }

    /**
     * Highway bounce: sprint-jump, open the elytra right away, land, repeat. Every sprint jump adds speed and the
     * elytra's drag is tiny, so speed builds up. Pitch is held by a silent rotation (camera stays free), and our
     * elytra physics use it (LivingEntityMixin) so the client flies what the server sees.
     */
    private void bounce() {
        var p = mc.player;
        if (!p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) || !p.input.keyPresses.forward()) return;
        if (!p.isSprinting()) p.setSprinting(true);
        Prism.rotations().request(p.getYRot(), bouncePitch.getFloat(), 80);
        if (p.onGround()) dev.prismglass.manager.MovementHooks.requestJump();
        else if (!p.isFallFlying() && !dev.prismglass.manager.MovementHooks.glidePending()) dev.prismglass.manager.MovementHooks.requestGlide();
    }

    private void grim() {
        var p = mc.player;
        if (!p.isFallFlying()) { holdY = Double.NaN; return; }
        boolean up = p.input.keyPresses.jump(), down = p.input.keyPresses.shift();
        Vec3 vel = p.getDeltaMovement();
        float pitch;
        if (up) {
            pitch = -climbPitch.getFloat();
            holdY = Double.NaN;
        } else if (down) {
            pitch = divePitch.getFloat();
            holdY = Double.NaN;
        } else if (holdHeight.get()) {
            if (Double.isNaN(holdY)) holdY = p.getY();
            // climb/sink toward the held height at most 0.5 b/t; pitch up (negative) when we sink too fast
            double wantVy = Mth.clamp((holdY - p.getY()) * 0.1, -0.5, 0.5);
            pitch = (float) Mth.clamp((vel.y - wantVy) * 70.0, -30.0, 25.0);
        } else {
            return; // plain elytra: your own look flies it
        }
        Prism.rotations().request(p.getYRot(), pitch, 80);

        double speed = vel.length();
        if (autoRocket.get() && (speed < minSpeed.get() || up && speed < minSpeed.get() * 1.4)) rocket();
    }

    private void rocket() {
        var p = mc.player;
        if (ticks - lastRocket < rocketGap.getInt()) return;
        InteractionHand hand;
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) hand = InteractionHand.OFF_HAND;
        else if (p.getMainHandItem().is(Items.FIREWORK_ROCKET)) hand = InteractionHand.MAIN_HAND;
        else {
            int slot = InvUtil.findHotbar(Items.FIREWORK_ROCKET);
            if (slot == -1 || !Prism.guard().canSwitchSlot()) return;
            restoreSlot = p.getInventory().getSelectedSlot();
            InvUtil.swap(slot, false);
            hand = InteractionHand.MAIN_HAND;
        }
        mc.gameMode.useItem(p, hand);
        lastRocket = ticks;
    }

    @Override
    public void onMove(MoveEvent e) {
        var p = mc.player;
        if (!p.isFallFlying() || mode.is("Grim") || mode.is("Bounce")) return;
        if (mode.is("Control")) {
            double[] d = EntityUtil.directionSpeed(speed.get(), p.getYRot());
            e.setHorizontal(d[0], d[1]);
            e.y = p.input.keyPresses.jump() ? vSpeed.get() : p.input.keyPresses.shift() ? -vSpeed.get() : 0;
            p.setDeltaMovement(e.x, e.y, e.z);
        } else if (p.input.keyPresses.jump()) {
            Vec3 look = RotationUtil.direction(p.getYRot(), p.getXRot());
            Vec3 v = p.getDeltaMovement().add(look.scale(0.05));
            double h = Math.hypot(v.x, v.z);
            if (h > speed.get()) v = new Vec3(v.x / h * speed.get(), v.y, v.z / h * speed.get());
            p.setDeltaMovement(v);
        }
    }

    @Override public String getInfo() { return mode.get(); }
}
