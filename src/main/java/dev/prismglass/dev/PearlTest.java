package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.render.PearlPredict;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/** Dev only (-Ppearltest): AutoPearl aims at target spots in a flat world; logs predicted vs real landing. */
public final class PearlTest {
    private static final Minecraft mc = Minecraft.getInstance();
    /** Targets relative to where we stand: dx, dy (above the ground), dz. dy > 0 gets a pillar to land on. */
    private static final double[][] TARGETS = {{12, 0, 8}, {-28, 0, 10}, {0, 0, -45}, {35, 0, 30}, {20, 6, 0}, {-8, 0, -6}};
    private static int ticks, t, index, phase, wait;
    private static boolean requested;
    private static Vec3 target, before;
    private static double sumErr;
    private static int landed;

    private PearlTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[pearltest] {}", s); }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismpearl-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        PearlPredict pp = Prism.modules().get(PearlPredict.class);
        if (t == 30) {
            for (var m : Prism.modules().all()) {
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            }
            Prism.anticheat().preset.parse("Custom");
            Prism.anticheat().update();
            Prism.anticheat().preset.parse("Grim");
            Prism.anticheat().update();
            pp.setEnabled(true);
            pp.autoPearl.set(true);
            run(s, "item replace entity @p hotbar.3 with ender_pearl 16");
            if (mc.screen != null) mc.setScreen(null);
            phase = 0;
            wait = 40;
        }
        if (t < 40) return;
        if (wait > 0) { wait--; return; }
        if (index == TARGETS.length) {
            log(String.format("targets: %d/%d landed, average error %.2f blocks", landed, TARGETS.length, landed == 0 ? -1 : sumErr / landed));
            // the .pearltest command path: a real flying "enemy" pearl, detected and followed by AutoPearl
            before = mc.player.position();
            pp.lastThrow = null;
            pp.spawnTestPearl(25);
            index++;
            t = 2000;
            return;
        }
        if (index > TARGETS.length) {
            if (mc.player.position().distanceTo(before) > 2.5 && mc.player.onGround()) {
                log(String.format(".pearltest: followed, moved %.1f blocks", mc.player.position().distanceTo(before)));
                Runtime.getRuntime().halt(0);
            }
            if (t > 2000 + 200) { log(".pearltest: not followed (lastThrow=" + pp.lastThrow + ")"); Runtime.getRuntime().halt(0); }
            return;
        }
        double[] d = TARGETS[index];
        if (phase == 0) {
            // ground is the top of the flat world under us; a pillar if the target is raised
            Vec3 p = mc.player.position();
            int gx = (int) Math.floor(p.x + d[0]), gz = (int) Math.floor(p.z + d[2]), gy = (int) Math.floor(p.y);
            if (d[1] > 0) run(s, String.format("fill %d %d %d %d %d %d stone", gx - 1, gy, gz - 1, gx + 1, gy + (int) d[1] - 1, gz + 1));
            target = new Vec3(gx + 0.5, gy + d[1], gz + 0.5);
            phase = 1;
            wait = 10;
            return;
        }
        if (phase == 1) {
            before = mc.player.position();
            pp.lastThrow = null;
            pp.debugFollow(target);
            phase = 2;
            t = 1000; // reuse t as a timeout clock below
            return;
        }
        if (phase == 2) {
            boolean moved = mc.player.position().distanceTo(before) > 2.5;
            if (moved && mc.player.onGround()) {
                double err = mc.player.position().distanceTo(target);
                double predErr = pp.lastThrow == null ? -1 : mc.player.position().distanceTo(pp.lastThrow);
                log(String.format("target %d (%.0f %.0f %.0f, %.0fm away): landed %.2f from target, %.2f from our prediction",
                    index, d[0], d[1], d[2], target.distanceTo(before), err, predErr));
                sumErr += err;
                landed++;
                next();
            } else if (t > 1000 + 200) {
                log("target " + index + ": no landing (lastThrow=" + pp.lastThrow + ")");
                next();
            }
        }
    }

    private static void next() {
        index++;
        phase = 0;
        wait = 30; // pearl cooldown
    }
}
