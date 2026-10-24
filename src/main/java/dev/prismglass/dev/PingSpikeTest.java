package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.movement.PingSpike;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * Dev only (-Ppingspiketest): the pingspike command on the integrated server. Checks, via the real command handler:
 * A) toggle: client moves are NOT seen by the server while held, and arrive after the toggle off;
 * B) timed (pingspike 20): a server teleport is NOT applied on the client while held, the spike ends by itself
 *    after exactly 20 ticks and the teleport lands right after;
 * C) MaxSeconds cap releases a toggle spike on its own;
 * D) disconnect while holding drops the packets and leaves the module off.
 * Logs "[pingspiketest]", exits.
 */
public final class PingSpikeTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t, passed, failed, mark, phase;
    private static boolean requested;
    private static double x0, sx0;

    private PingSpikeTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[pingspiketest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void cmd(String c) { Prism.commands().handle(Prism.commands().getPrefix() + c); }

    private static Vec3 serverPos() {
        ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
        return sp.position();
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismspike-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (phase >= 100) return;
        if (ticks > 2500) { check("test finished in time", false); finish(); return; }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) {
            if (phase == 90 && mc.level == null) { // D: world gone
                PingSpike ps = Prism.modules().get(PingSpike.class);
                check("D: module is off after the disconnect", !ps.isEnabled());
                check("D: no hold left over", ps.getInfo() == null);
                finish();
            }
            return;
        }
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        PingSpike ps = Prism.modules().get(PingSpike.class);

        if (t == 20) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            Prism.anticheat().preset.parse("Custom");
            Prism.anticheat().update();
            run(s, "gamerule advance_time false");
            run(s, "tp @p 0.5 -60 0.5");
        }
        if (t < 40) return;

        // ---- A: toggle, outbound hold
        if (phase == 0) {
            x0 = mc.player.getX();
            sx0 = serverPos().x;
            cmd("pingspike");
            check("A: pingspike enables the spike", ps.isEnabled());
            mark = t;
            phase = 1;
        }
        if (phase == 1) {
            mc.player.setPos(mc.player.getX() + 0.3, mc.player.getY(), mc.player.getZ()); // sends a movement packet each tick
            if (t == mark + 12) {
                double moved = mc.player.getX() - x0;
                double srv = serverPos().x - sx0;
                log("A: client moved " + moved + ", server saw " + srv + ", info=" + ps.getInfo());
                check("A: client moved ~3.6 blocks", moved > 3);
                check("A: server did NOT see the movement while held (" + srv + ")", Math.abs(srv) < 0.5);
                check("A: packets are counted as held (" + ps.getInfo() + ")", ps.getInfo() != null && !ps.getInfo().startsWith("0"));
                cmd("pingspike");
                check("A: second pingspike disables it", !ps.isEnabled());
                mark = t;
                phase = 2;
            }
        }
        if (phase == 2 && t == mark + 6) {
            double srv = serverPos().x - sx0;
            check("A: server got the movement after release (" + srv + ", client " + (mc.player.getX() - x0) + ")", srv > 2.5);
            mark = t;
            phase = 3;
        }

        // ---- B: timed, inbound hold + exact tick count
        if (phase == 3 && t == mark + 5) {
            x0 = mc.player.getX();
            cmd("pingspike 20");
            check("B: pingspike 20 enables it", ps.isEnabled());
            run(s, "tp @p " + (x0 + 8) + " -60 0.5");
            mark = t;
            phase = 4;
        }
        if (phase == 4) {
            if (t == mark + 10) {
                check("B: still holding after 10 ticks", ps.isEnabled());
                String info = ps.getInfo();
                check("B: the server teleport was HELD, not lost (" + info + ")", info != null && info.matches("\\d+↑ [1-9]\\d*↓.*"));
                check("B: server teleport NOT applied on the client yet (dx " + (mc.player.getX() - x0) + ")", Math.abs(mc.player.getX() - x0) < 1.0);
            }
            if (!ps.isEnabled()) {
                check("B: ended by itself after exactly 20 ticks (" + (t - mark) + ")", t - mark == 20);
                mark = t;
                phase = 5;
            } else if (t > mark + 60) { check("B: timed spike ended at all", false); phase = 99; }
        }
        if (phase == 5 && t == mark + 5) {
            check("B: no spike left over", ps.getInfo() == null);
            check("B: teleport landed after release (dx " + (mc.player.getX() - x0) + ")", mc.player.getX() - x0 > 6);
            ps.maxSeconds.set(1.0);
            cmd("pingspike");
            mark = t;
            phase = 6;
        }

        // ---- C: MaxSeconds cap
        if (phase == 6) {
            if (!ps.isEnabled()) {
                check("C: cap released the toggle spike after " + (t - mark) + " ticks (~20)", t - mark >= 18 && t - mark <= 26);
                ps.maxSeconds.set(10.0);
                mark = t;
                phase = 7;
            } else if (t > mark + 60) { check("C: cap released at all", false); phase = 99; }
        }

        // ---- D: disconnect while holding
        if (phase == 7 && t == mark + 5) {
            cmd("pingspike");
            check("D: spike on before disconnecting", ps.isEnabled());
            mc.player.setPos(mc.player.getX() + 0.3, mc.player.getY(), mc.player.getZ());
            mark = t;
            phase = 8;
        }
        if (phase == 8 && t == mark + 3) {
            mc.level.disconnect(Component.literal("pingspike test"));
            phase = 90;
        }
        if (phase == 99) finish();
    }

    private static void finish() {
        log("RESULT passed=" + passed + " failed=" + failed);
        phase = 100;
        mc.stop();
    }
}
