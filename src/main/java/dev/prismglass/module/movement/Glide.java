package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class Glide extends Module {
    public final NumberSetting fall = num("FallSpeed", 0.08, 0.01, 0.5, 0.01, "Max fall speed.");
    public Glide() { super("Glide", "Fall slowly. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var v = mc.player.getDeltaMovement();
        if (!mc.player.onGround() && v.y < -fall.get() && !mc.player.isFallFlying()) {
            mc.player.setDeltaMovement(v.x, -fall.get(), v.z);
        }
    }
}
