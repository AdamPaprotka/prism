package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Jumps at the very edge of a block. The jump goes through real input (MovementHooks), so it is legit. */
public class Parkour extends Module {
    private boolean jump;

    public Parkour() { super("Parkour", "Jump at the very edge of blocks (real input, safe).", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var p = mc.player;
        jump = false;
        if (!p.onGround() || p.isShiftKeyDown() || mc.player.input.keyPresses.jump() || !EntityUtil.isMoving()) return;
        Vec3 v = p.getDeltaMovement();
        AABB next = p.getBoundingBox().move(v.x, -0.5, v.z).deflate(0.001, 0, 0.001);
        if (mc.level.noCollision(p, next)) jump = true;
    }

    public boolean wantsJump() { return jump; }
}
