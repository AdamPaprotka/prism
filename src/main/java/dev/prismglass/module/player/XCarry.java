package dev.prismglass.module.player;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

/**
 * Four extra slots: items left in the 2x2 crafting grid stay there after closing the inventory, because
 * the close packet the server empties the grid on is never sent. Turning the module off sends it, so
 * the items come back into your inventory (or drop if it's full). Logging out with items in the grid
 * drops them on the ground where you logged out (vanilla Player.remove).
 */
public class XCarry extends Module {
    public XCarry() { super("XCarry", "Keeps items in your crafting grid as extra storage.", Category.PLAYER); }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (e.packet instanceof ServerboundContainerClosePacket p && p.getContainerId() == mc.player.inventoryMenu.containerId) e.cancel();
    }

    @Override
    public void onDisable() {
        // the server closes whatever menu is open on this packet, so only send it when none is
        if (mc.player.containerMenu == mc.player.inventoryMenu) mc.player.connection.send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
    }
}
