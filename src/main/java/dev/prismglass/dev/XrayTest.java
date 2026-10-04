package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.xray.OreSimulator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.util.*;

/**
 * Dev-only accuracy test for the seed ore simulator (./gradlew runClient -Pxraytest): creates a vanilla world
 * with a fixed seed (no anti-xray in singleplayer, so the client sees the truth), predicts ores for the chunks
 * around spawn and compares against the real blocks. Logs precision/recall per ore and exits.
 */
public final class XrayTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final long SEED = 1234567890123L;
    private static int ticks, inWorld;
    private static boolean requested;
    private static int phase;

    private XrayTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[xraytest] {}", s); }

    private static void tick() {
        ticks++;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismxray-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SPECTATOR, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(SEED, true, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), null);
            log("creating world seed " + SEED);
            return;
        }
        if (mc.player == null || mc.level == null) return;
        if (phase == 2) { perf(); return; }
        if (++inWorld != 200) return; // let chunks load
        if (phase == 1) {
            OreSimulator nether = OreSimulator.create(mc.level, SEED);
            evaluate(nether, new HashSet<>(List.of(Blocks.ANCIENT_DEBRIS, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE)), "nether");
            Runtime.getRuntime().halt(0);
        }

        Set<Block> targets = new HashSet<>(List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
        OreSimulator sim = OreSimulator.create(mc.level, SEED);
        if (sim == null) { log("FAIL: simulator null"); Runtime.getRuntime().halt(1); }

        evaluate(sim, targets, "overworld");
        if (phase == 0) {
            phase = 2; // tick-time check, then the nether
            return;
        }
        Runtime.getRuntime().halt(0);
    }

    private static int perfTick;
    private static final double[] perfMax = new double[2], perfSum = new double[2];
    private static double seedCreateMs;

    /** Worst per-tick cost of the Xray module (Exposed, then Seed) at ChunkRange 5: lag spikes show up here. */
    private static void perf() {
        var x = Prism.modules().get(dev.prismglass.module.render.Xray.class);
        if (perfTick == 0) { x.range.set(5.0); x.mode.parse("Exposed"); x.seed.set(String.valueOf(SEED)); }
        if (perfTick == 100) x.mode.parse("Seed");
        long t0 = System.nanoTime();
        x.onTick();
        double ms = (System.nanoTime() - t0) / 1e6;
        int m = perfTick < 100 ? 0 : 1;
        if (perfTick == 100) seedCreateMs = ms; // one-time simulator setup, reported apart
        else { perfMax[m] = Math.max(perfMax[m], ms); perfSum[m] += ms; }
        if (++perfTick < 200) return;
        log(String.format("perf Exposed: max %.2f ms/tick, avg %.3f ms/tick, %s", perfMax[0], perfSum[0] / 100, x.getInfo()));
        log(String.format("perf Seed: setup %.1f ms, max %.2f ms/tick, avg %.3f ms/tick", seedCreateMs, perfMax[1], perfSum[1] / 99));
        phase = 1;
        inWorld = 0;
        var server = mc.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
            "execute in minecraft:the_nether run tp @p 0 32 0"));
    }

    /** Simulate 7x7 chunks (so veins spilling over from neighbours are included), score the inner 5x5. */
    private static void evaluate(OreSimulator sim, Set<Block> targets, String label) {
        if (sim == null) { log("FAIL: simulator null for " + label); return; }
        ChunkPos center = mc.player.chunkPosition();
        Map<BlockPos, Block> predicted = new HashMap<>();
        long t0 = System.nanoTime();
        int simulated = 0;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            ChunkPos cp = new ChunkPos(center.x() + dx, center.z() + dz);
            if (mc.level.getChunkSource().getChunkNow(cp.x(), cp.z()) == null) continue;
            simulated++;
            for (OreSimulator.Ore ore : sim.simulate(cp, targets)) predicted.put(ore.pos(), ore.block());
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        Map<String, int[]> stats = new TreeMap<>(); // predicted, correct, actual
        int minX = (center.x() - 2) << 4, maxX = ((center.x() + 2) << 4) + 15, minZ = (center.z() - 2) << 4, maxZ = ((center.z() + 2) << 4) + 15;
        for (var e : predicted.entrySet()) {
            BlockPos p = e.getKey();
            if (p.getX() < minX || p.getX() > maxX || p.getZ() < minZ || p.getZ() > maxZ) continue;
            int[] s = stats.computeIfAbsent(name(e.getValue()), k -> new int[3]);
            s[0]++;
            if (mc.level.getBlockState(p).getBlock() == e.getValue()) s[1]++;
        }
        for (BlockPos p : BlockPos.betweenClosed(minX, mc.level.getMinY(), minZ, maxX, Math.min(mc.level.getMaxY(), 130), maxZ)) {
            Block b = mc.level.getBlockState(p).getBlock();
            if (targets.contains(b)) stats.computeIfAbsent(name(b), k -> new int[3])[2]++;
        }
        log(String.format("%s: simulated %d chunks in %.0f ms", label, simulated, ms));
        for (var e : stats.entrySet()) {
            int[] s = e.getValue();
            log(String.format("%-14s predicted %5d  correct %5d  actual %5d  precision %5.1f%%  recall %5.1f%%",
                e.getKey(), s[0], s[1], s[2], 100.0 * s[1] / Math.max(1, s[0]), 100.0 * s[1] / Math.max(1, s[2])));
        }
    }

    private static String name(Block b) {
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
        return id.replace("deepslate_", "").replace("_ore", "");
    }
}
