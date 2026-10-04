package dev.prismglass.module.movement;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * Removes the item-use slowdown.
 *
 * <p>"Grim": Grim's prediction always also tries "not using an item" for every tick, so an unslowed tick still
 * matches. Its NoSlow check only flags when two checked ticks in a row are unslowed. Skipping the slowdown on
 * every other tick never makes two in a row: about 3x the vanilla speed while eating/blocking/drawing.
 * "NCP" re-sends the held slot while using (resets NCP's use tracking). "Vanilla" is full speed [Grim-unsafe].
 */
public class NoSlow extends Module {
    public final ModeSetting mode = mode("Mode", "Grim", "Grim = unslowed every other tick (Grim-safe). Vanilla = always unslowed [Grim-unsafe]. NCP = slot resync.", "Grim", "Vanilla", "NCP");
    public final BoolSetting itemsSetting = bool("Items", true, "No slowdown while eating/blocking/drawing.");
    private int useTicks;

    public NoSlow() {
        super("NoSlow", "No slowdown while using items (Grim mode is Grim-safe).", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        useTicks = mc.player.isUsingItem() ? useTicks + 1 : 0;
        if (mode.is("NCP") && mc.player.isUsingItem() && dev.prismglass.Prism.guard().canSwitchSlot()) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
        }
    }

    /** Whether to skip the slowdown this tick (modules tick before the player moves). */
    public boolean items() {
        if (!itemsSetting.get()) return false;
        if (!mode.is("Grim")) return true;
        // when the item finishes, Grim still counts us as slowed for a tick until the server's metadata
        // arrives, so the last ticks before that stay slowed (otherwise: two unslowed ticks in a row)
        // and the first use tick stays slowed too (Grim marks us slowed from the use packet that same tick)
        return useTicks >= 2 && useTicks % 2 == 0 && mc.player.getUseItemRemainingTicks() > 3;
    }

    @Override public String getInfo() { return mode.get(); }
}
