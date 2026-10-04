package dev.prismglass.module.player;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

/**
 * Raises the ping the server measures by holding keep-alive replies before sending them.
 *
 * <ul>
 *   <li>Replies keep their original order and correct IDs (Grim BadPacketsO only checks the ID was sent).</li>
 *   <li>Delay is capped far below the vanilla 15 s keep-alive timeout.</li>
 *   <li>A daemon scheduler releases them on time, independent of the game loop (pause, lag spikes).</li>
 *   <li>Transactions (pong) are left alone by default: Grim uses them to sync your world/position, delaying
 *       them makes Grim treat you as a laggy player and can trip timer/transaction checks. Optional, unsafe.</li>
 * </ul>
 */
public class PingSpoof extends Module {
    public final NumberSetting delay = num("Delay", 200, 0, 5000, 10, "Extra ping in ms.");
    public final NumberSetting jitter = num("Jitter", 20, 0, 500, 5, "Random +- ms so it looks like a real connection.");
    public final BoolSetting transactions = bool("Transactions", false, "Also delay ping/pong transactions. [Grim-unsafe]");

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Prism-PingSpoof");
        t.setDaemon(true);
        return t;
    });

    /** Packets we are re-sending ourselves; they must pass through untouched. */
    private final Set<Packet<?>> releasing = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private volatile long lastReleaseAt;

    public PingSpoof() { super("PingSpoof", "Makes your ping look higher.", Category.PLAYER); }

    @Override public void onEnable() { lastReleaseAt = 0; }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (releasing.remove(e.packet)) return;
        boolean keepAlive = e.packet instanceof ServerboundKeepAlivePacket;
        boolean pong = transactions.get() && e.packet instanceof ServerboundPongPacket;
        if (!keepAlive && !pong) return;
        Connection conn = mc.getConnection() == null ? null : mc.getConnection().getConnection();
        if (conn == null) return;

        long now = System.currentTimeMillis();
        long wanted = delay.getInt() + (jitter.getInt() > 0 ? ThreadLocalRandom.current().nextInt(-jitter.getInt(), jitter.getInt() + 1) : 0);
        wanted = Math.max(0, Math.min(5000, wanted));
        // never reorder: each reply leaves no earlier than the previous one
        long at;
        synchronized (this) {
            at = Math.max(now + wanted, lastReleaseAt);
            lastReleaseAt = at;
        }
        e.cancel();
        Packet<?> packet = e.packet;
        // held replies always go out at their time, even if the module is turned off meanwhile:
        // dropping a keep-alive would get us timed out
        SCHEDULER.schedule(() -> {
            if (!conn.isConnected()) return;
            releasing.add(packet);
            conn.send(packet);
        }, at - now, TimeUnit.MILLISECONDS);
    }

    @Override public String getInfo() { return "+" + delay.getInt() + "ms"; }
}
