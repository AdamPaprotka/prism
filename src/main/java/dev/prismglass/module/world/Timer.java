package dev.prismglass.module.world;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

/**
 * Speeds up the client tick rate.
 *
 * <p>Grim's Timer check adds 50 ms to a balance for every tick the client sends and flags once the balance gets
 * ahead of real time; the balance is never allowed to fall more than ~120 ms + ping behind. So the average tick
 * rate can never beat 1.0, but a client running at 1.0 always has that 120 ms + ping in hand. "Balance" mode
 * models the same balance on our side: it bursts at Speed while there is time banked, otherwise runs at 1.0, and
 * refills the bank by running slower while you stand still. Vanilla is a plain multiplier [Grim-unsafe].
 */
public class Timer extends Module {
    public final ModeSetting mode = mode("Mode", "Balance", "Balance = Grim-safe bursts (bank ~120ms + ping, spend it at Speed). NCP = steady, kept under NoCheatPlus' packet limits (about 1.05x). Vanilla = constant [unsafe].", "Balance", "NCP", "Vanilla");
    public final NumberSetting speed = num("Speed", 2.0, 0.1, 10, 0.05, "Tick speed multiplier.");
    public final NumberSetting charge = num("ChargeSpeed", 0.6, 0.1, 1, 0.05, "Balance: game speed while refilling the bank (only when standing still; 1 = never refill).");
    public final NumberSetting margin = num("Margin", 20, 0, 100, 5, "Balance: milliseconds kept in hand for network jitter.");

    /** Our copy of Grim's balance: how far behind real time the server thinks our ticks are (ms); NaN = unknown. */
    private double bankMs = Double.NaN;
    private long lastTickNs;
    private float current = 1f;
    /** NCP mode: when recent ticks went out (ns), for NoCheatPlus' MorePackets windows. */
    private final java.util.ArrayDeque<Long> sent = new java.util.ArrayDeque<>();

    public Timer() { super("Timer", "Changes game speed (Balance mode is Grim-safe).", Category.WORLD); }

    /**
     * Called by the client tick loop right before every tick (MinecraftClientMixin). A frame can run several ticks
     * back to back, so the bank is checked per tick, not per frame: false skips a tick that would put Grim's
     * balance ahead of real time (Balance mode only; a skipped tick is just like lag).
     */
    public boolean beforeTick() {
        if (mc.player == null || mc.getConnection() == null) {
            bankMs = Double.NaN; // a fresh join starts with the balance clamped: full bank
            lastTickNs = 0;
            return true;
        }
        long now = System.nanoTime();
        double cap = capMs();
        if (Double.isNaN(bankMs)) bankMs = cap;
        double elapsed = lastTickNs == 0 ? 50.0 : (now - lastTickNs) / 1e6;
        // Grim never lets the balance fall further behind than the cap; each tick then costs 50 ms
        double before = Math.min(cap, bankMs + elapsed);
        double after = before - 50.0;
        if (isEnabled() && mode.is("NCP")) {
            // NoCheatPlus MorePackets: <= 22 packets/s averaged over 6 s, > 15 in any 500 ms flags at once.
            // Keep under 21/s and 13 per half second; a tick that would cross either is skipped (like lag).
            while (!sent.isEmpty() && now - sent.peekFirst() > 6_000_000_000L) sent.pollFirst();
            int recent = 0;
            for (var it = sent.descendingIterator(); it.hasNext(); ) { if (now - it.next() <= 500_000_000L) recent++; else break; }
            if (sent.size() >= 126 || recent >= 13) return false;
            sent.addLast(now);
            current = speed.getFloat();
            lastTickNs = now;
            return true;
        }
        boolean balance = isEnabled() && mode.is("Balance");
        if (balance && after < margin.get() * 0.5) return false;
        bankMs = after;
        lastTickNs = now;

        if (!balance) { current = speed.getFloat(); return true; }
        float s = speed.getFloat();
        double cost = 50.0 - 50.0 / s; // bank spent by one tick at speed s
        if (s > 1f && bankMs - cost >= margin.get()) current = s;
        else if (before < cap - 5 && idle()) current = charge.getFloat(); // refill only until the bank is full
        else current = Math.min(1f, s);
        return true;
    }

    /** The furthest behind real time Grim lets the balance fall: 120 ms clock drift plus the round trip. */
    private double capMs() {
        int ping = 0;
        var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info != null) ping = info.getLatency();
        return 120 + Math.max(0, ping) * 0.8;
    }

    private boolean idle() {
        return mc.player.onGround() && !EntityUtil.isMoving() && !mc.player.input.keyPresses.jump();
    }

    public float speed() {
        if (mode.is("Balance")) return current;
        if (mode.is("NCP")) return Math.min(speed.getFloat(), 1.05f); // the steady rate NCP accepts
        return speed.getFloat();
    }

    /** Dev/HUD: milliseconds of burst left. */
    public double bank() { return bankMs; }

    @Override
    public String getInfo() {
        if (mode.is("NCP")) return String.format("NCP %.2fx", speed());
        if (mode.is("Balance") && !Double.isNaN(bankMs)) return String.format("%.1fx %dms", current, Math.round(bankMs));
        return speed.display();
    }
}
