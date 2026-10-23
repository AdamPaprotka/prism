package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.ChatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

/**
 * Lag switch: while on, EVERY packet to and from the server is held instead of delivered, then everything is
 * released in its original order. ",pingspike" toggles, ",pingspike <ticks>" holds for that many ticks.
 *
 * <ul>
 *   <li>Outbound is held at the very end of {@link Prism#onPacketSend} (after PacketGuard/RotationManager ran), so
 *       their state keeps following the real timeline; the flush bypasses them again.</li>
 *   <li>Inbound is held at the start of {@link Prism#onPacketReceive} (netty thread). On release the packets run
 *       through the same hooks, then are handled on the main thread, so the Prism hooks see each packet once.</li>
 *   <li>The server kicks a client that does not answer a keep-alive within 15 s, so a spike is auto-released after
 *       MaxSeconds (default 10). 0 disables the cap.</li>
 *   <li>Disconnect or a different connection/world while holding drops the held packets: they belong to a session
 *       that is gone and must never reach a new one.</li>
 * </ul>
 */
public class PingSpike extends Module {
    public final NumberSetting maxSeconds = num("MaxSeconds", 10, 0, 60, 1, "Auto-release after this long (server kicks at ~15 s without a keep-alive reply). 0 = no cap.");

    private final Object lock = new Object();
    private final List<Packet<?>> out = new ArrayList<>();
    private final List<Packet<ClientGamePacketListener>> in = new ArrayList<>();
    private volatile boolean holdingOut, holdingIn;
    /** The thread currently flushing outbound packets; its sends must pass untouched. */
    private volatile Thread flusher;
    /** The thread currently replaying held inbound packets through the hooks (they must not be held again). */
    private volatile Thread drainer;

    private int limit, elapsed;
    private long startNanos;
    private Object startLevel;
    private Connection startConn;
    private boolean aborting;
    private int totalOut, totalIn;

    public PingSpike() { super("PingSpike", "Lag switch: hold all packets in and out, then release them.", Category.MOVEMENT); }

    // ---- control ------------------------------------------------------------------------

    /** Start holding. ticks = 0: until stopped (or the MaxSeconds cap). Restarts the countdown if already holding. */
    public void start(int ticks) {
        limit = ticks;
        elapsed = 0;
        if (isEnabled()) {
            ChatUtil.info("Ping spike §atimer reset§7: holding for " + describe(ticks));
            return;
        }
        setEnabled(true);
    }

    public void stop() { setEnabled(false); }

    private String describe(int ticks) {
        return ticks <= 0 ? "until you toggle it off" : ticks + " ticks (" + String.format(Locale.ROOT, "%.2f", ticks / 20.0) + " s)";
    }

    @Override
    public void onEnable() {
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) { setEnabledSilently(false); return; }
        synchronized (lock) {
            out.clear();
            in.clear();
            startConn = listener.getConnection();
            startLevel = mc.level;
            startNanos = System.nanoTime();
            totalOut = totalIn = 0;
            holdingOut = true;
            holdingIn = true;
        }
        ChatUtil.good("Ping spike §aON§7 - holding all packets, " + describe(limit)
            + (maxSeconds.getInt() > 0 ? " §8(auto-release at " + maxSeconds.getInt() + " s)" : ""));
    }

    @Override
    public void onDisable() {
        if (aborting) return;
        release("released");
    }

    @Override public void onWorldJoin() {
        // a config saved mid-spike loads as "enabled" without any hold behind it
        if (isEnabled() && !holdingOut && !holdingIn) setEnabledSilently(false);
    }

    @Override
    public void onTick() {
        if (!holdingOut && !holdingIn) return;
        ClientPacketListener listener = mc.getConnection();
        if (listener == null || listener.getConnection() != startConn || mc.level != startLevel) {
            abort("world or connection changed");
            return;
        }
        if (limit > 0 && ++elapsed >= limit) { setEnabled(false); return; }
        int cap = maxSeconds.getInt();
        if (cap > 0 && System.nanoTime() - startNanos >= cap * 1_000_000_000L) {
            ChatUtil.error("Ping spike hit the " + cap + " s cap, releasing so the server does not time you out");
            setEnabled(false);
        }
    }

    /** Disconnect (or anything else that kills the session): throw the held packets away, never flush them. */
    public void abort(String why) {
        if (!holdingOut && !holdingIn) return;
        int o, i;
        synchronized (lock) {
            o = out.size();
            i = in.size();
            out.clear();
            in.clear();
            holdingOut = holdingIn = false;
        }
        Prism.LOG.info("[pingspike] aborted ({}), dropped {} out / {} in", why, o, i);
        aborting = true;
        try { setEnabled(false); } finally { aborting = false; }
        ChatUtil.error("Ping spike §coff§7 - " + why + ", dropped " + o + " out / " + i + " in held packets");
    }

    // ---- hooks called from Prism ----------------------------------------------------------

    /** True while this thread is flushing held packets: Prism's packet hooks must let them through untouched. */
    public boolean flushing() { return flusher == Thread.currentThread(); }

    /** Outbound, last step of Prism.onPacketSend. @return true if the packet was taken (cancel it). */
    public boolean holdOutgoing(Packet<?> packet) {
        // the integrated server's own connection runs through the same send() hook: only OUR packets (serverbound)
        if (!holdingOut || flushing() || packet.type().flow() != PacketFlow.SERVERBOUND) return false;
        synchronized (lock) {
            if (!holdingOut) return false; // released while we waited for the lock
            out.add(packet);
            totalOut++;
        }
        return true;
    }

    /** Inbound (netty thread), first step of Prism.onPacketReceive. @return true if the packet was taken (cancel it). */
    @SuppressWarnings("unchecked")
    public boolean holdIncoming(Packet<?> packet) {
        if (!holdingIn || drainer == Thread.currentThread()) return false;
        synchronized (lock) {
            if (!holdingIn) return false;
            in.add((Packet<ClientGamePacketListener>) packet);
            totalIn++;
        }
        return true;
    }

    // ---- release --------------------------------------------------------------------------

    private void release(String verb) {
        ClientPacketListener listener = mc.getConnection();
        boolean usable = listener != null && listener.getConnection() == startConn && listener.getConnection().isConnected();
        int sentOut = 0, sentIn = 0;
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;

        // outbound first, in order; sends from other threads wait on the lock and queue up behind the flush
        synchronized (lock) {
            List<Packet<?>> o = new ArrayList<>(out);
            out.clear();
            if (usable) {
                flusher = Thread.currentThread();
                try {
                    for (Packet<?> p : o) {
                        try { listener.send(p); sentOut++; }
                        catch (Throwable t) { Prism.LOG.error("[pingspike] outbound flush", t); }
                    }
                } finally { flusher = null; }
            }
            holdingOut = false;
        }

        // inbound: same hooks as live traffic, then the real handler, in arrival order
        synchronized (lock) {
            List<Packet<ClientGamePacketListener>> i = new ArrayList<>(in);
            in.clear();
            if (usable && mc.level == startLevel) {
                drainer = Thread.currentThread();
                try {
                    for (Packet<ClientGamePacketListener> p : i) {
                        try {
                            if (!Prism.onPacketReceive(p)) { p.handle(listener); sentIn++; }
                        } catch (Throwable t) { Prism.LOG.error("[pingspike] inbound flush", t); }
                    }
                } finally { drainer = null; }
            }
            holdingIn = false;
        }

        Prism.LOG.info("[pingspike] {} after {} ms: out {} / in {}", verb, ms, sentOut, sentIn);
        ChatUtil.info("Ping spike §coff§7 - " + verb + " §f" + sentOut + "§7 out / §f" + sentIn + "§7 in after §f" + ms + " ms");
        limit = 0;
    }

    @Override
    public String getInfo() {
        if (!holdingOut && !holdingIn) return null;
        String left = limit > 0 ? " " + Math.max(0, limit - elapsed) + "t" : "";
        return totalOut + "↑ " + totalIn + "↓" + left;
    }
}
