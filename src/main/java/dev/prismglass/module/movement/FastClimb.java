package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class FastClimb extends Module {
    public final NumberSetting speed = num("Speed", 0.3, 0.12, 1, 0.01, "Ladder climb speed.");
    public FastClimb() { super("FastClimb", "Climb ladders/vines faster. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player.onClimbable() && mc.player.horizontalCollision && EntityUtil.isMoving()) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, speed.get(), mc.player.getDeltaMovement().z);
        }
    }
}
