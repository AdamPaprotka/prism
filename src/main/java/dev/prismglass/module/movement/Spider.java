package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class Spider extends Module {
    public final NumberSetting speed = num("Speed", 0.2, 0.05, 1, 0.05, "Climb speed.");
    public Spider() { super("Spider", "Climb walls. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player.horizontalCollision && EntityUtil.isMoving()) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, speed.get(), mc.player.getDeltaMovement().z);
        }
    }
}
