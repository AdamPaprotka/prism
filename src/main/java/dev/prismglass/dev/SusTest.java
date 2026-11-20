package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.world.SusChunks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev only (-Psustest): SusChunks against hand-built geodes in a flat world. Chunk (2,2) gets budding amethyst with a
 * full cluster on every face (grown out), chunk (-3,2) a fresh-looking one (a small bud, a medium bud, one cluster,
 * the rest empty). The grown chunk must be flagged at 100%, the fresh one not, and the area must be the
 * simulation-distance square around (2,2). Then Palette mode is checked on the same chunks. Logs "[sustest]", exits.
 */
public final class SusTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t, passed, failed;
    private static boolean requested;

    private SusTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[sustest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    /** Budding amethyst at x,y,z with the given block (or air) on each face: up, down, north, south, west, east. */
    private static void geode(MinecraftServer s, int x, int y, int z, String... faces) {
        run(s, "setblock " + x + " " + y + " " + z + " budding_amethyst");
        int[][] off = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        String[] facing = {"up", "down", "north", "south", "west", "east"};
        for (int i = 0; i < 6; i++) {
            String b = faces[i];
            String state = b.equals("air") ? "air" : b + "[facing=" + facing[i] + "]";
            run(s, "setblock " + (x + off[i][0]) + " " + (y + off[i][1]) + " " + (z + off[i][2]) + " " + state);
        }
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismsus-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        SusChunks sus = Prism.modules().get(SusChunks.class);

        if (t == 20) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            run(s, "gamerule advance_time false");
            run(s, "gamerule random_tick_speed 0"); // nothing may grow on its own during the test
            run(s, "time set day");
            run(s, "tp @p 8.5 -60 8.5 0 0");
            sus.method.parse("Blocks");
            sus.growth.parse("60");
            sus.minFaces.parse("6");
            sus.simDistance.parse("8");
            sus.setEnabled(true);
        }
        if (t == 40) {
            String c = "amethyst_cluster";
            geode(s, 40, -55, 40, c, c, c, c, c, c);
            geode(s, 44, -55, 40, c, c, c, c, c, c);
            geode(s, -40, -55, 40, "small_amethyst_bud", "air", "medium_amethyst_bud", "air", c, "air");
            geode(s, -44, -55, 40, "air", "air", "air", "air", "air", "air");
        }
        if (t == 80) {
            var grown = sus.results().get(ChunkPos.pack(2, 2));
            var fresh = sus.results().get(ChunkPos.pack(-3, 2));
            log("grown chunk: " + grown);
            log("fresh chunk: " + fresh);
            check("grown geode chunk scanned at 100% (12 faces)", grown != null && grown.open() == 12 && grown.clusters() == 12);
            check("grown chunk flagged", grown != null && sus.flagged(grown));
            check("fresh geode chunk: 12 faces, 1 cluster, 2 buds, not flagged",
                fresh != null && fresh.open() == 12 && fresh.clusters() == 1 && fresh.buds() == 2 && !sus.flagged(fresh));
            log("areas: " + sus.areas());
            check("area = sim-distance square around (2,2)", sus.areas().size() == 1
                && sus.areas().get(0).equals(new SusChunks.Area(2 - 8, 2 - 8, 2 + 8, 2 + 8, 1)));
            mc.player.setYRot(-45);
            mc.player.setXRot(10);
        }
        if (t == 90) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-suschunks.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
        if (t == 100) {
            sus.method.parse("Palette");
            sus.setEnabled(false);
            sus.setEnabled(true); // rescan everything loaded
        }
        if (t == 160) {
            var grown = sus.results().get(ChunkPos.pack(2, 2));
            var fresh = sus.results().get(ChunkPos.pack(-3, 2));
            check("palette: clusters and no buds = grown, flagged", grown != null && grown.open() < 0 && sus.flagged(grown));
            check("palette: buds present = not flagged", fresh != null && !sus.flagged(fresh));
        }
        if (t == 170) {
            // light: a fresh-looking geode (2 clusters, 6 large buds) in chunk (-3,-3)
            String c = "amethyst_cluster", l = "large_amethyst_bud";
            geode(s, -40, -55, -40, c, l, l, "air", l, "air");
            geode(s, -44, -55, -40, c, l, l, "air", l, "air");
            // kelp: 6 grown tops (age 25) in chunk (5,5), 6 fresh ones (age 21) in chunk (5,-6), all under water
            run(s, "fill 80 -60 80 95 -52 95 water");
            run(s, "fill 80 -60 -96 95 -52 -81 water");
            for (int i = 0; i < 6; i++) {
                int x = 81 + 2 * i;
                for (int y = -60; y < -57; y++) {
                    run(s, "setblock " + x + " " + y + " 88 kelp_plant");
                    run(s, "setblock " + x + " " + y + " -88 kelp_plant");
                }
                run(s, "setblock " + x + " -57 88 kelp[age=25]");
                run(s, "setblock " + x + " -57 -88 kelp[age=21]");
            }
            sus.method.parse("Light");
            sus.minFaces.parse("6");
        }
        if (t == 200) { sus.setEnabled(false); sus.setEnabled(true); }
        if (t == 260) {
            var grown = sus.results().get(ChunkPos.pack(2, 2));
            var fresh = sus.results().get(ChunkPos.pack(-3, -3));
            log("light grown: " + grown);
            log("light fresh: " + fresh);
            check("light: grown chunk = 12 cluster light sources, flagged", grown != null && grown.light() && grown.clusters() == 12 && grown.open() == 12 && sus.flagged(grown));
            check("light: 2 clusters + 6 large buds counted from light, not flagged", fresh != null && fresh.light() && fresh.clusters() == 2 && fresh.buds() == 6 && !sus.flagged(fresh));
            var kg = sus.kelpResults().get(ChunkPos.pack(5, 5));
            var kf = sus.kelpResults().get(ChunkPos.pack(5, -6));
            log("kelp grown: " + kg + " fresh: " + kf);
            check("kelp: 6 tops at age 25 = grown, flagged", kg != null && kg.grown() == 6 && kg.fresh() == 0 && sus.flagged(kg));
            check("kelp: 6 tops at age 21 = fresh, not flagged", kf != null && kf.grown() == 0 && kf.fresh() == 6 && !sus.flagged(kf));
            // anti-xray view: every amethyst block in the client's copy turned to stone, light left as the server sent it
            for (int[] cxz : new int[][]{{2, 2}, {-3, -3}}) {
                var chunk = mc.level.getChunk(cxz[0], cxz[1]);
                for (var section : chunk.getSections()) {
                    if (section == null || section.hasOnlyAir()) continue;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var st = section.getBlockState(x, y, z);
                        if (st.is(net.minecraft.world.level.block.Blocks.AMETHYST_CLUSTER) || st.is(net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST)
                            || st.is(net.minecraft.world.level.block.Blocks.LARGE_AMETHYST_BUD))
                            section.setBlockState(x, y, z, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), false);
                    }
                }
            }
            sus.method.parse("Auto");
            sus.setEnabled(false);
            sus.setEnabled(true);
        }
        if (t == 320) {
            var grown = sus.results().get(ChunkPos.pack(2, 2));
            var fresh = sus.results().get(ChunkPos.pack(-3, -3));
            log("hidden grown: " + grown + " fresh: " + fresh);
            check("anti-xray: blocks hidden, Auto falls back to light and still flags the grown chunk", grown != null && grown.light() && sus.flagged(grown));
            check("anti-xray: the fresh one stays unflagged", fresh == null || !sus.flagged(fresh));
        }
        if (t == 330) {
            log("RESULT passed=" + passed + " failed=" + failed);
            mc.stop();
        }
    }
}
