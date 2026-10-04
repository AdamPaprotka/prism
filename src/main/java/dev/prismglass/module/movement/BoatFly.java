package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class BoatFly extends Module {
    public final NumberSetting speed = num("Speed", 1.5, 0.1, 5, 0.1, "Horizontal speed.");
    public final NumberSetting vSpeed = num("VerticalSpeed", 0.5, 0.05, 3, 0.05, "Vertical speed (jump up, sprint down).");
    public BoatFly() { super("BoatFly", "Fly with boats/vehicles. [unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var v = mc.player.getVehicle();
        if (v == null) return;
        v.setYRot(mc.player.getYRot());
        double[] d = EntityUtil.directionSpeed(speed.get(), mc.player.getYRot());
        double y = mc.player.input.keyPresses.jump() ? vSpeed.get() : mc.player.input.keyPresses.sprint() ? -vSpeed.get() : 0;
        v.setDeltaMovement(d[0], y, d[1]);
    }
}
