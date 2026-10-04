package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Elytra control. [Grim-unsafe: Elytra A-I] */
public class ElytraFly extends Module {
    public final ModeSetting mode = mode("Mode", "Control", "Control = no gravity, direct steering. Boost = accelerate along look.", "Control", "Boost");
    public final NumberSetting speed = num("Speed", 1.8, 0.1, 5, 0.05, "Horizontal speed.");
    public final NumberSetting vSpeed = num("VerticalSpeed", 1.0, 0.1, 3, 0.05, "Up/down speed (Control).");
    public final BoolSetting autoStart = bool("AutoStart", true, "Open the elytra automatically when falling.");

    public ElytraFly() { super("ElytraFly", "Better elytra flight.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var p = mc.player;
        if (autoStart.get() && !p.isFallFlying() && !p.onGround() && p.getDeltaMovement().y < -0.1
            && p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && !p.getAbilities().flying
            && !mc.player.input.keyPresses.jump()) {
            // jump release -> press, so vanilla sends START_FALL_FLYING itself (Grim ElytraB-safe)
            dev.prismglass.manager.MovementHooks.requestGlide();
        }
    }

    @Override
    public void onMove(MoveEvent e) {
        var p = mc.player;
        if (!p.isFallFlying()) return;
        if (mode.is("Control")) {
            double[] d = EntityUtil.directionSpeed(speed.get(), p.getYRot());
            e.setHorizontal(d[0], d[1]);
            e.y = mc.player.input.keyPresses.jump() ? vSpeed.get() : mc.player.input.keyPresses.shift() ? -vSpeed.get() : 0;
            p.setDeltaMovement(e.x, e.y, e.z);
        } else if (mc.player.input.keyPresses.jump()) {
            Vec3 look = RotationUtil.direction(p.getYRot(), p.getXRot());
            Vec3 v = p.getDeltaMovement().add(look.scale(0.05));
            double h = Math.hypot(v.x, v.z);
            if (h > speed.get()) v = new Vec3(v.x / h * speed.get(), v.y, v.z / h * speed.get());
            p.setDeltaMovement(v);
        }
    }

    @Override public String getInfo() { return mode.get(); }
}
