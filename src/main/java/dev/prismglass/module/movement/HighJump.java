package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class HighJump extends Module {
    public final NumberSetting multiplier = num("Multiplier", 1.5, 1, 5, 0.1, "Jump height multiplier.");
    public HighJump() { super("HighJump", "Jump higher. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onMove(MoveEvent e) {
        if (e.y > 0.4 && e.y < 0.43 && mc.player.onGround()) {
            e.y *= multiplier.get();
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, e.y, mc.player.getDeltaMovement().z);
        }
    }
}
