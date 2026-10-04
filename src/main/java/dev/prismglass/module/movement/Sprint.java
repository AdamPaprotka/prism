package dev.prismglass.module.movement;

import net.minecraft.world.effect.MobEffects;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

/**
 * Sprints whenever vanilla would allow it. Legit mode checks every condition Grim's Sprint A-G
 * look at (hunger, sneaking, using, wall collision, blindness, gliding, water) so it never flags.
 */
public class Sprint extends Module {
    public final ModeSetting mode = mode("Mode", "Legit", "Rage sprints in all directions. [Grim-unsafe]", "Legit", "Rage");

    public Sprint() { super("Sprint", "Automatically sprint.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var p = mc.player;
        boolean moving = mode.is("Rage") ? EntityUtil.isMoving() : p.input.getMoveVector().y > 0.8f;
        boolean can = moving && !p.isShiftKeyDown() && !p.isUsingItem() && !p.horizontalCollision
            && p.getFoodData().getFoodLevel() > 6 && !p.isFallFlying() && !p.isInWater()
            && !p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
        if (can && !p.isSprinting()) p.setSprinting(true);
    }

    @Override public String getInfo() { return mode.get(); }
}
