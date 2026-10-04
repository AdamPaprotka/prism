package dev.prismglass.event;

import net.minecraft.network.protocol.Packet;

/**
 * Fired for every outgoing (main thread) and incoming (netty thread!) packet.
 * Receive handlers must stay cheap and must not touch the world directly - schedule
 * work with {@code mc.execute(...)} if needed.
 */
public class PacketEvent {
    public final Packet<?> packet;
    private boolean cancelled;

    public PacketEvent(Packet<?> packet) { this.packet = packet; }

    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }
}
