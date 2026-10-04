package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.xray.StructurePredictor;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only accuracy test for StructurePredictor (./gradlew runClient -Pstructtest): fixed-seed vanilla world; for each
 * dimension, predicted starts in a square of chunks vs the integrated server's real structure starts (generated to
 * the STRUCTURE_STARTS stage only). Logs precision/recall per structure category and exits.
 */
public final class StructureTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final long SEED = 1234567890123L;
    private static int ticks, inWorld;
    private static boolean requested;

    private StructureTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[structtest] {}", s); }

    private static void tick() {
        ticks++;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismstruct-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SPECTATOR, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(SEED, true, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        inWorld++;
        MinecraftServer server = mc.getSingleplayerServer();
        if (inWorld == 60) {
            evaluate(server, Level.OVERWORLD, 0, 0, 48);
            evaluate(server, Level.NETHER, 0, 0, 40);
            evaluate(server, Level.END, 90, 90, 40); // outer islands, where end cities are
            // module check: seed + both modes, look at the nearest predicted village
            Prism.modules().get(dev.prismglass.module.render.Xray.class).seed.set(String.valueOf(SEED));
            var sf = Prism.modules().get(dev.prismglass.module.world.StructureFinder.class);
            sf.setEnabled(true);
        }
        if (inWorld == 200) {
            var sf = Prism.modules().get(dev.prismglass.module.world.StructureFinder.class);
            log("module info: " + sf.getInfo());
            var p = StructurePredictor.create(Level.OVERWORLD, SEED);
            var villages = p.scan(mc.player.chunkPosition().x(), mc.player.chunkPosition().z(), 48, c -> c.equals("village"));
            if (!villages.isEmpty()) {
                var v = villages.stream().min(java.util.Comparator.comparingDouble(h -> h.pos().distSqr(mc.player.blockPosition()))).get();
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "tp @p " + v.pos().getX() + " " + (v.pos().getY() + 40) + " " + (v.pos().getZ() + 60) + " facing " + v.pos().getX() + " " + v.pos().getY() + " " + v.pos().getZ()));
                log("looking at village " + v.pos());
            }
        }
        if (inWorld == 400) {
            log("module info after tp: " + Prism.modules().get(dev.prismglass.module.world.StructureFinder.class).getInfo());
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-structures.png", mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        }
        if (inWorld == 401) {
            dev.prismglass.module.client.AutoTranslate.debugTranslate("Hola, ¿cómo estás? Vamos a la base.", "en")
                .thenAccept(r -> log("translate es->en: " + r));
            dev.prismglass.module.client.AutoTranslate.debugTranslate("Cześć, gdzie jest baza?", "en")
                .thenAccept(r -> log("translate pl->en: " + r));
        }
        if (inWorld == 420) {
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }

    private static void evaluate(MinecraftServer server, ResourceKey<Level> dim, int cx, int cz, int r) {
        long t0 = System.nanoTime();
        StructurePredictor predictor = StructurePredictor.create(dim, SEED);
        if (predictor == null) { log("FAIL: no predictor for " + dim.identifier()); return; }
        List<StructurePredictor.Hit> hits = predictor.scan(cx, cz, r, c -> true);
        double predictMs = (System.nanoTime() - t0) / 1e6;

        Set<String> predicted = new HashSet<>();
        for (StructurePredictor.Hit h : hits) predicted.add(h.id() + "@" + h.chunk().x() + "," + h.chunk().z());

        Set<String> actual = server.submit(() -> {
            ServerLevel level = server.getLevel(dim);
            var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Set<String> out = new HashSet<>();
            for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
                var chunk = level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, true);
                for (var e : chunk.getAllStarts().entrySet()) {
                    if (!e.getValue().isValid()) continue;
                    var key = structures.getKey(e.getKey());
                    out.add((key == null ? "unknown" : key.getPath()) + "@" + x + "," + z);
                }
            }
            return out;
        }).join();

        Map<String, int[]> stats = new TreeMap<>(); // predicted, correct, actual
        for (String p : predicted) {
            int[] s = stats.computeIfAbsent(StructurePredictor.category(p.substring(0, p.indexOf('@'))), k -> new int[3]);
            s[0]++;
            if (actual.contains(p)) s[1]++;
        }
        for (String a : actual) stats.computeIfAbsent(StructurePredictor.category(a.substring(0, a.indexOf('@'))), k -> new int[3])[2]++;
        log(String.format("%s: %d chunks square r=%d, predicted %d in %.0f ms", dim.identifier().getPath(), (2 * r + 1) * (2 * r + 1), r, hits.size(), predictMs));
        for (var e : stats.entrySet()) {
            int[] s = e.getValue();
            log(String.format("  %-15s predicted %4d  correct %4d  actual %4d  precision %5.1f%%  recall %5.1f%%", e.getKey(), s[0], s[1], s[2],
                100.0 * s[1] / Math.max(1, s[0]), 100.0 * s[1] / Math.max(1, s[2])));
        }
        List<String> misses = new ArrayList<>();
        for (String a : actual) if (!predicted.contains(a) && !a.startsWith("mineshaft")) misses.add(a);
        List<String> wrong = new ArrayList<>();
        for (String p : predicted) if (!actual.contains(p) && !p.startsWith("mineshaft")) wrong.add(p);
        if (!misses.isEmpty()) log("  missed: " + misses.subList(0, Math.min(12, misses.size())));
        for (String m : misses.subList(0, Math.min(4, misses.size()))) {
            String[] xz = m.substring(m.indexOf('@') + 1).split(",");
            log("    " + predictor.debug(m.substring(0, m.indexOf('@')), new ChunkPos(Integer.parseInt(xz[0]), Integer.parseInt(xz[1]))));
        }
        if (!wrong.isEmpty()) log("  wrong:  " + wrong.subList(0, Math.min(12, wrong.size())));
    }
}
