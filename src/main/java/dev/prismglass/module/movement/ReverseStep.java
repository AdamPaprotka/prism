package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.phys.AABB;

public class ReverseStep extends Module {
    public final NumberSetting height = num("Height", 2.0, 0.5, 4, 0.5, "Max drop to snap down.");
    public final NumberSetting speed = num("Speed", 1.0, 0.1, 3, 0.1, "Downward speed.");
    private boolean wasOnGround;

    public ReverseStep() { super("ReverseStep", "Snap down off ledges. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var p = mc.player;
        if (wasOnGround && !p.onGround() && p.getDeltaMovement().y <= 0 && !mc.player.input.keyPresses.jump()
            && !p.isInWater() && !p.isInLava() && !p.onClimbable()) {
            AABB below = p.getBoundingBox().move(0, -height.get(), 0);
            if (!mc.level.noCollision(p, below)) p.setDeltaMovement(p.getDeltaMovement().x, -speed.get(), p.getDeltaMovement().z);
        }
        wasOnGround = p.onGround();
    }
}
