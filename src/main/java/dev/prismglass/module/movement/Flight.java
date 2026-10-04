package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class Flight extends Module {
    public final ModeSetting mode = mode("Mode", "Vanilla", "Flight method. [unsafe on any AC]", "Vanilla", "Creative");
    public final NumberSetting speed = num("Speed", 1.0, 0.1, 10, 0.1, "Horizontal speed.");
    public final NumberSetting vSpeed = num("VerticalSpeed", 0.6, 0.1, 5, 0.1, "Vertical speed.");
    public final BoolSetting antiKick = bool("AntiKick", true, "Drop slightly every 40 ticks to dodge the vanilla fly-kick.");

    private int ticks;

    public Flight() { super("Flight", "Fly in survival.", Category.MOVEMENT); }

    @Override
    public void onDisable() {
        if (!mc.player.isCreative() && !mc.player.isSpectator()) {
            mc.player.getAbilities().flying = false;
            mc.player.getAbilities().mayfly = false;
        }
    }

    @Override
    public void onTick() {
        ticks++;
        if (mode.is("Creative")) {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.getAbilities().setFlyingSpeed(speed.getFloat() * 0.05f);
        }
    }

    @Override
    public void onMove(MoveEvent e) {
        if (!mode.is("Vanilla")) return;
        double[] d = EntityUtil.directionSpeed(speed.get(), mc.player.getYRot());
        e.setHorizontal(d[0], d[1]);
        double y = 0;
        if (mc.player.input.keyPresses.jump()) y += vSpeed.get();
        if (mc.player.input.keyPresses.shift()) y -= vSpeed.get();
        if (antiKick.get() && ticks % 40 == 0 && y == 0) y = -0.04;
        e.y = y;
        mc.player.setDeltaMovement(0, 0, 0);
    }

    @Override public String getInfo() { return mode.get(); }
}
