package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class AirJump extends Module {
    private boolean wasPressed;
    public AirJump() { super("AirJump", "Jump mid-air. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        boolean pressed = mc.player.input.keyPresses.jump();
        if (pressed && !wasPressed && !mc.player.onGround()) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.42, mc.player.getDeltaMovement().z);
        }
        wasPressed = pressed;
    }
}
