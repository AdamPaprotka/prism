package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.movement.PacketFly;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Dev only (-Ppflytest -Pmp=localhost:25599): on a real (dedicated) server, PacketFly up 3 s, forward 3 s, hover 7 s
 * (past vanilla's 4 s floating kick). Logs client position and setbacks; the server's view is read over RCON.
 */
public final class PacketFlyTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int t;
    private static Vec3 start;

    private PacketFlyTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[pflytest] {}", s); }

    private static void tick() {
        if (mc.player == null || mc.getConnection() == null) {
            if (t > 0) { log("DISCONNECTED at t=" + t); Runtime.getRuntime().halt(0); }
            return;
        }
        mc.options.pauseOnLostFocus = false;
        t++;
        PacketFly pf = Prism.modules().get(PacketFly.class);
        if (t == 100) {
            start = mc.player.position();
            log("joined as " + mc.player.getName().getString() + " at " + start);
            pf.mode.parse(System.getProperty("prismglass.pflymode", "Vanilla"));
            pf.setEnabled(true);
            mc.options.keyJump.setDown(true);
        }
        if (t == 160) { mc.options.keyJump.setDown(false); mc.options.keyUp.setDown(true); log(String.format("after up: client %s, setbacks %d", mc.player.position(), pf.setbacks)); }
        if (t == 220) { mc.options.keyUp.setDown(false); log(String.format("after forward: client %s, setbacks %d", mc.player.position(), pf.setbacks)); }
        if (t == 360) {
            log(String.format("after 7s hover: client %s, setbacks %d, moved %.1f blocks", mc.player.position(), pf.setbacks, mc.player.position().distanceTo(start)));
            log("CHECK_SERVER");
        }
        if (t == 420) { log("done"); Runtime.getRuntime().halt(0); }
    }
}
