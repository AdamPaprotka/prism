package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class LongJump extends Module {
    public final NumberSetting boost = num("Boost", 1.6, 1, 4, 0.05, "Horizontal boost on jump.");
    public final BoolSetting autoDisable = bool("AutoDisable", true, "Turn off after landing.");
    private boolean jumped;
    private int airTicks;

    public LongJump() { super("LongJump", "Long jumps. [Grim-unsafe]", Category.MOVEMENT); }

    @Override public void onEnable() { jumped = false; airTicks = 0; }

    @Override
    public void onMove(MoveEvent e) {
        var p = mc.player;
        if (!jumped && p.onGround() && EntityUtil.isMoving()) {
            e.y = 0.42;
            p.setDeltaMovement(p.getDeltaMovement().x, 0.42, p.getDeltaMovement().z);
            double[] d = EntityUtil.directionSpeed(EntityUtil.baseSpeed() * boost.get() * 2, p.getYRot());
            e.setHorizontal(d[0], d[1]);
            jumped = true;
        } else if (jumped) {
            airTicks++;
            if (p.onGround() && airTicks > 2 && autoDisable.get()) toggle();
        }
    }
}
