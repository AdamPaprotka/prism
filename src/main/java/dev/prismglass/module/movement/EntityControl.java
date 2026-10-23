package dev.prismglass.module.movement;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import net.minecraft.world.entity.Entity;

/**
 * Steer rideables the vanilla rules won't let you: horses, donkeys, mules and camels without a saddle, pigs without a
 * carrot on a stick, striders without warped fungus (EntityControlMixin opens getControllingPassenger to you), and
 * every horse jump at full charge. The vanilla server only accepts vehicle movement from the passenger it thinks
 * steers, so without the saddle / stick it puts the vehicle back: for servers that don't check that. [unsafe]
 */
public class EntityControl extends Module {
    public final BoolSetting noSaddle = bool("NoSaddle", true, "Steer horses, donkeys, mules and camels without a saddle.");
    public final BoolSetting noStick = bool("NoStick", true, "Steer pigs and striders without the stick.");
    public final BoolSetting maxJump = bool("MaxJump", true, "Horse jumps are always fully charged.");

    public EntityControl() { super("EntityControl", "Steer horses/pigs/striders without saddle or stick, max horse jump. [unsafe]", Category.MOVEMENT); }

    /** Mixins: we ride this and the module is on. */
    public boolean controls(Entity vehicle) {
        return isEnabled() && mc.player != null && vehicle.getFirstPassenger() == mc.player;
    }
}
