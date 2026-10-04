package dev.prismglass.manager;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * Grim MultiActionsC flags (and cancels) any inventory click while the server thinks you are sprinting or
 * holding a movement key (1.21.2+ clients send their keys in PlayerInput packets). This tracks what we told
 * the server, and when a module wants to click, releases movement for a tick first - exactly what a legit
 * player does - so the click lands while the server sees us standing still.
 */
public final class InventoryGuard {
    private final Minecraft mc = Minecraft.getInstance();

    private volatile boolean serverSprinting;
    private volatile boolean serverMoving;
    private long tick;
    private long holdUntil = -1;

    /** Observe outgoing packets (called from PacketGuard). */
    public void onSend(PacketEvent e) {
        if (e.packet instanceof ServerboundPlayerInputPacket in) {
            Input p = in.input();
            serverMoving = p.forward() || p.backward() || p.left() || p.right();
        } else if (e.packet instanceof ServerboundPlayerCommandPacket c) {
            if (c.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) serverSprinting = true;
            else if (c.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) serverSprinting = false;
        }
    }

    public void onTick() { tick++; }

    public void reset() { serverSprinting = serverMoving = false; holdUntil = -1; }

    /**
     * @return true if an inventory click is safe right now. If not, movement is released for the next ticks so
     * it becomes safe; call again next tick.
     */
    public boolean ready() {
        if (!Prism.anticheat().invStrict.get()) return true;
        holdUntil = tick + 2;
        return !serverSprinting && !serverMoving;
    }

    /** MovementHooks asks this while building input: true = keep movement keys released. */
    public boolean holdingStill() { return tick <= holdUntil; }
}
