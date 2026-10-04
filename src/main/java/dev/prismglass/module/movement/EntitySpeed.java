package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class EntitySpeed extends Module {
    public final NumberSetting speed = num("Speed", 1.0, 0.1, 5, 0.1, "Speed while riding.");
    public EntitySpeed() { super("EntitySpeed", "Faster mounts. [unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var v = mc.player.getVehicle();
        if (v == null || !EntityUtil.isMoving()) return;
        double[] d = EntityUtil.directionSpeed(speed.get(), mc.player.getYRot());
        v.setDeltaMovement(d[0], v.getDeltaMovement().y, d[1]);
    }
}
