package dev.prismglass.module.movement;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.item.Items;

/**
 * Steer rideables the vanilla rules won't let you: horses, donkeys, mules and camels without a saddle, pigs without a
 * carrot on a stick, striders without warped fungus (EntityControlMixin opens getControllingPassenger to you), and
 * every horse jump at full charge. The vanilla server only accepts vehicle movement from the passenger it thinks
 * steers, so without the saddle / stick the ride is client-side there: 26.3 servers even snap the vehicle back when
 * they get its movement, which ClientOnly avoids by not sending it. [unsafe]
 */
public class EntityControl extends Module {
    public final BoolSetting noSaddle = bool("NoSaddle", true, "Steer horses, donkeys, mules and camels without a saddle.");
    public final BoolSetting noStick = bool("NoStick", true, "Steer pigs and striders without the stick.");
    public final BoolSetting maxJump = bool("MaxJump", true, "Horse jumps are always fully charged.");
    public final BoolSetting clientOnly = bool("ClientOnly", true,
        "Don't send the movement of a ride the server doesn't think you steer (26.3 snaps it back). Turn off on servers that don't check.");

    public EntityControl() { super("EntityControl", "Steer horses/pigs/striders without saddle or stick, max horse jump. [unsafe]", Category.MOVEMENT); }

    /** Mixins: we ride this and the module is on. */
    public boolean controls(Entity vehicle) {
        return isEnabled() && mc.player != null && vehicle.getFirstPassenger() == mc.player;
    }

    /** Vanilla wouldn't let us steer this, so only the patched client does. */
    private boolean steeredOnlyByUs(Entity vehicle) {
        if (vehicle instanceof AbstractHorse horse) return !horse.isSaddled();
        if (vehicle instanceof Pig) return !mc.player.isHolding(Items.CARROT_ON_A_STICK);
        if (vehicle instanceof Strider) return !mc.player.isHolding(Items.WARPED_FUNGUS_ON_A_STICK);
        return false;
    }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (!clientOnly.get() || !(e.packet instanceof ServerboundMoveVehiclePacket) || mc.player == null) return;
        Entity vehicle = mc.player.getRootVehicle();
        if (vehicle != mc.player && controls(vehicle) && steeredOnlyByUs(vehicle)) e.cancel();
    }
}
