package dev.prismglass.module.movement;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

/**
 * Removes the item-use slowdown.
 *
 * <p>"Grim": Grim's prediction always also tries "not using an item" for every tick, so an unslowed tick still
 * matches. Its NoSlow check only flags when two checked ticks in a row are unslowed. Skipping the slowdown on
 * every other tick never makes two in a row: about 3x the vanilla speed while eating/blocking/drawing.
 * "NCP" re-sends the held slot while using (resets NCP's use tracking). "Vanilla" is full speed [Grim-unsafe].
 *
 * <p>The two public full-speed Grim bypasses, for servers on older Grim builds (both read from Grim's source; 2.3.74
 * patched them, so the default Grim mode stays the half-tick one):
 * <ul>
 *   <li>"GrimOffhand": eat/block in the main hand and send an off-hand use packet every tick. Old Grim cleared its
 *       "using an item" state whenever a use packet was for an item it couldn't use (an empty off hand); the server
 *       just ignores the empty-hand use and keeps eating. 2.3.74 only clears on a cooldown item in the same hand.</li>
 *   <li>"GrimSwitch": eat in the off hand and swap the hotbar slot away and back every tick. Old Grim reset the use on
 *       any slot change; the server only stops main-hand uses on a slot change, so off-hand eating goes on. 2.3.74
 *       skips the reset for off-hand uses.</li>
 * </ul>
 * Both run the item at full speed while the trick applies; use "Grim" on current Grim.
 */
public class NoSlow extends Module {
    public final ModeSetting mode = mode("Mode", "Grim", "Grim = unslowed every other tick (safe on Grim 2.3.74). GrimOffhand / GrimSwitch = full speed on older Grim builds (main-hand use + off-hand packet / off-hand use + slot swap). Vanilla = always unslowed [Grim-unsafe]. NCP = slot resync.",
        "Grim", "GrimOffhand", "GrimSwitch", "Vanilla", "NCP");
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
        if (useTicks >= 1 && trickApplies()) {
            if (mode.is("GrimOffhand")) {
                // the server ignores a use with an empty hand; old Grim dropped its slowed state on it
                try (var prediction = mc.level.getBlockStatePredictionHandler().startPredicting()) {
                    mc.player.connection.send(new ServerboundUseItemPacket(InteractionHand.OFF_HAND, prediction.currentSequence(),
                        mc.player.getYRot(), mc.player.getXRot()));
                }
                tricks++;
            } else if (mode.is("GrimSwitch")) {
                int slot = mc.player.getInventory().getSelectedSlot();
                mc.player.connection.send(new ServerboundSetCarriedItemPacket((slot + 1) % 9));
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
                tricks++;
            }
        }
    }

    /** Packets the old-Grim tricks sent (dev test). */
    public int tricks;

    /** GrimOffhand needs a main-hand use and an empty off hand; GrimSwitch an off-hand use. */
    private boolean trickApplies() {
        if (!mc.player.isUsingItem()) return false;
        if (mode.is("GrimOffhand")) return mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND && mc.player.getOffhandItem().isEmpty();
        if (mode.is("GrimSwitch")) return mc.player.getUsedItemHand() == InteractionHand.OFF_HAND;
        return false;
    }

    /** Whether to skip the slowdown this tick (modules tick before the player moves). */
    public boolean items() {
        if (!itemsSetting.get()) return false;
        if (mode.is("GrimOffhand") || mode.is("GrimSwitch")) return trickApplies() ? useTicks >= 2 : grimTick();
        if (!mode.is("Grim")) return true;
        return grimTick();
    }

    private boolean grimTick() {
        // when the item finishes, Grim still counts us as slowed for a tick until the server's metadata
        // arrives, so the last ticks before that stay slowed (otherwise: two unslowed ticks in a row)
        // and the first use tick stays slowed too (Grim marks us slowed from the use packet that same tick)
        return useTicks >= 2 && useTicks % 2 == 0 && mc.player.getUseItemRemainingTicks() > 3;
    }

    @Override public String getInfo() { return mode.get(); }
}
