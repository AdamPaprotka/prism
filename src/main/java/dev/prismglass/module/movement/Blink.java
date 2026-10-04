package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import java.util.ArrayList;
import java.util.List;

/** Holds movement packets and sends them all at once. [Grim: Timer flags on release] */
public class Blink extends Module {
    public final NumberSetting pulse = num("Pulse", 0, 0, 100, 1, "Auto-release every N ticks (0 = only on disable).");
    private final List<Packet<?>> held = new ArrayList<>();
    private boolean releasing;
    private int ticks;

    public Blink() { super("Blink", "Fake lag: freeze your server position.", Category.MOVEMENT); }

    @Override public void onEnable() { synchronized (held) { held.clear(); } ticks = 0; }
    @Override public void onDisable() { release(); }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (releasing) return;
        if (e.packet instanceof ServerboundMovePlayerPacket) {
            synchronized (held) { held.add(e.packet); }
            e.cancel();
        }
    }

    @Override
    public void onTick() {
        if (pulse.getInt() > 0 && ++ticks >= pulse.getInt()) { ticks = 0; release(); }
    }

    private void release() {
        synchronized (held) {
            if (mc.player != null) {
                releasing = true;
                for (Packet<?> p : held) mc.player.connection.send(p);
                releasing = false;
            }
            held.clear();
        }
    }

    @Override public String getInfo() { synchronized (held) { return String.valueOf(held.size()); } }
}
