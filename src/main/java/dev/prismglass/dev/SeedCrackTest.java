package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.xray.SeedMath;
import dev.prismglass.xray.StructureMatcher;
import dev.prismglass.xray.StructurePredictor;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only test for StructureMatcher + SeedCracker (./gradlew runClient -Pcracktest): fixed-seed vanilla world.
 * <ol>
 *   <li>The cracker math on the server's real structure starts (structure seed, hashed seed, biome check).</li>
 *   <li>Flies to real jungle temples, desert pyramids, swamp huts and villages and checks every exact find the
 *       client makes against the server's real starts (any find that isn't a real start is a false positive).</li>
 *   <li>Places lone "signature" blocks (tripwire hook, bell, sea lantern, crying obsidian...) and checks nothing
 *       gets found.</li>
 *   <li>SeedCracker end to end: its status once it has seen the structures.</li>
 * </ol>
 */
public final class SeedCrackTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final long SEED = 1234567890123L;
    private static final int RADIUS = 64;
    private static final Set<String> KINDS = Set.of("jungle_pyramid", "desert_pyramid", "swamp_hut");
    private static int ticks, inWorld, targetIndex = -1, waitUntil;
    private static boolean requested;
    private static final Set<String> real = new HashSet<>();
    private static final List<BlockPos> targets = new ArrayList<>();
    private static final List<String> targetNames = new ArrayList<>();
    private static final Set<String> seenExact = new HashSet<>();
    /** Real village starts' town centre templates (id@x,z -> template path). */
    private static final Map<String, String> realTemplates = new HashMap<>();
    private static int tp, fp;

    private SeedCrackTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[cracktest] {}", s); }

    private static void cmd(MinecraftServer server, String c) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c));
    }

    private static void tick() {
        ticks++;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismcrack-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SPECTATOR, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(SEED, true, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        inWorld++;
        if (inWorld == 40) {
            Prism.modules().get(dev.prismglass.module.world.SeedCracker.class).setEnabled(true);
            StructureMatcher.prepare();
            collectReal(server);
            mathTest();
        }
        if (inWorld < 120) return;
        if (targetIndex < targets.size() && inWorld >= waitUntil) {
            if (targetIndex >= 0) {
                scanHere("at " + targetNames.get(targetIndex));
                String t = targetNames.get(targetIndex);
                String[] xz = t.substring(t.indexOf('@') + 1).split(",");
                String id = t.substring(0, t.indexOf('@'));
                log("  best layout: " + StructureMatcher.debug(mc.level, id.startsWith("village") ? id : id, new ChunkPos(Integer.parseInt(xz[0]), Integer.parseInt(xz[1]))));
            }
            targetIndex++;
            if (targetIndex < targets.size()) {
                BlockPos t = targets.get(targetIndex);
                cmd(server, "tp @p " + t.getX() + " " + (t.getY() + 40) + " " + t.getZ());
                waitUntil = inWorld + 120;
            } else {
                log(String.format("exact finds: %d real, %d FALSE", tp, fp));
                baitTest(server);
                waitUntil = inWorld + 60;
            }
            return;
        }
        if (targetIndex == targets.size() && inWorld == waitUntil) {
            int before = fp;
            scanHere("bait blocks");
            log(fp == before ? "bait: nothing found (good)" : "bait: FALSE FINDS");
            biomeTest();
        }
        var sf = Prism.modules().get(dev.prismglass.module.world.StructureFinder.class);
        if (targetIndex == targets.size() && inWorld == waitUntil + 20) {
            log("completion '.sf find oak': " + Prism.commands().complete(".sf find oak").options());
            log("completion '.structurefinder find vil': " + Prism.commands().complete(".structurefinder find vil").options());
            Prism.modules().get(dev.prismglass.module.render.Xray.class).seed.set(String.valueOf(SEED));
            // 300 blocks east of the plains village the flights ended at, so the nearest one isn't under us
            cmd(server, "tp @p 308 120 -312");
        }
        if (targetIndex == targets.size() && inWorld == waitUntil + 50) Prism.commands().handle(".sf find oak_village");
        if (targetIndex == targets.size() && inWorld == waitUntil + 100) {
            BlockPos t = sf.targetPos();
            log("find marker: " + t + (t == null ? " FAIL" : " real village_plains start near it: "
                + real.stream().filter(r -> r.startsWith("village_plains")).anyMatch(r -> {
                    String[] xz = r.substring(r.indexOf('@') + 1).split(",");
                    return Math.abs(Integer.parseInt(xz[0]) * 16 + 8 - t.getX()) <= 16 && Math.abs(Integer.parseInt(xz[1]) * 16 + 8 - t.getZ()) <= 16;
                })));
            if (t != null) cmd(server, "tp @p " + (t.getX() + 70) + " " + (t.getY() + 20) + " " + t.getZ());
        }
        if (targetIndex == targets.size() && inWorld == waitUntil + 140) {
            log("70m away, marker still there: " + (sf.targetPos() != null));
            BlockPos t = sf.targetPos();
            if (t != null) cmd(server, "tp @p " + (t.getX() + 30) + " " + (t.getY() + 20) + " " + t.getZ());
        }
        if (targetIndex == targets.size() && inWorld == waitUntil + 180) log("30m away, marker removed: " + (sf.targetPos() == null));
        if (targetIndex == targets.size() && inWorld == waitUntil + 185) Prism.commands().handle(".sf find zombie_village");
        if (targetIndex == targets.size() && inWorld == waitUntil + 600) {
            BlockPos t = sf.targetPos();
            if (t == null) log("zombie village find: no marker FAIL");
            else {
                String tpl = server.submit(() -> {
                    var start = server.getLevel(Level.OVERWORLD).getChunk(t.getX() >> 4, t.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS, true).getAllStarts().values().stream()
                        .filter(s -> s.isValid() && !s.getPieces().isEmpty()).findFirst().orElse(null);
                    if (start == null || !(start.getPieces().get(0) instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pe)
                        || !(pe.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement se)) return "no village start";
                    return se.getTemplateLocation().getPath();
                }).join();
                log("zombie village find: marker " + t + " (" + Math.round(Math.sqrt(t.distSqr(mc.player.blockPosition()))) + "m), server's town centre " + tpl
                    + (tpl.contains("/zombie/") ? " OK" : " FAIL"));
            }
        }
        if (targetIndex == targets.size() && inWorld == waitUntil + 620) {
            for (String line : Prism.modules().get(dev.prismglass.module.world.SeedCracker.class).describe()) log("seedcracker: " + line);
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }

    /** The server's real starts of the cracker's structures around 0,0; a few of each become flight targets. */
    private static void collectReal(MinecraftServer server) {
        Map<String, Integer> perKind = new HashMap<>();
        List<Object[]> found = server.submit(() -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            List<Object[]> out = new ArrayList<>();
            for (int x = -RADIUS; x <= RADIUS; x++) for (int z = -RADIUS; z <= RADIUS; z++) {
                var chunk = level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, true);
                for (var e : chunk.getAllStarts().entrySet()) {
                    if (!e.getValue().isValid()) continue;
                    var key = structures.getKey(e.getKey());
                    String id = key == null ? "?" : key.getPath();
                    if (!KINDS.contains(id) && !id.startsWith("village_")) continue;
                    out.add(new Object[]{id, x, z, e.getValue().getBoundingBox().getCenter()});
                    if (id.startsWith("village_") && !e.getValue().getPieces().isEmpty()
                        && e.getValue().getPieces().get(0) instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pe
                        && pe.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement se)
                        realTemplates.put(id + "@" + x + "," + z, se.getTemplateLocation().getPath());
                    if (KINDS.contains(id)) for (var piece : e.getValue().getPieces())
                        log("  server piece " + id + "@" + x + "," + z + ": " + piece.getBoundingBox() + " facing " + piece.getOrientation());
                }
            }
            return out;
        }).join();
        for (Object[] f : found) {
            String id = (String) f[0];
            real.add(id + "@" + f[1] + "," + f[2]);
            String kind = id.startsWith("village_") ? "village" : id;
            if (perKind.merge(kind, 1, Integer::sum) <= 3) { targets.add((BlockPos) f[3]); targetNames.add(id + "@" + f[1] + "," + f[2]); }
        }
        log("real starts: " + perKind + " -> " + targets.size() + " targets");
    }

    private static void mathTest() {
        List<SeedMath.Obs> obs = new ArrayList<>();
        for (String r : real) {
            String id = r.substring(0, r.indexOf('@'));
            String[] xz = r.substring(r.indexOf('@') + 1).split(",");
            SeedMath.Obs o = StructurePredictor.observation(id, new ChunkPos(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])));
            if (o == null) { log("FAIL: no placement for " + id); continue; }
            if (!SeedMath.fits(SEED, o)) log("FAIL: SeedMath says " + r + " doesn't fit the real seed");
            if (!StructurePredictor.vanillaFits(SEED, id, new ChunkPos(o.chunkX, o.chunkZ))) log("FAIL: vanilla says " + r + " doesn't fit");
            obs.add(o);
        }
        // town centre (and so zombie or not) predicted from the seed vs the server's real one
        var predictor = StructurePredictor.create(Level.OVERWORLD, SEED);
        int same = 0, zombiesReal = 0, zombiesPredicted = 0;
        Map<String, String> predicted = new HashMap<>();
        for (var h : predictor.scan(0, 0, RADIUS, c -> c.equals("village"))) {
            predicted.put(h.id() + "@" + h.chunk().x() + "," + h.chunk().z(), h.startTemplate());
            if (h.zombieVillage()) zombiesPredicted++;
        }
        for (var e : realTemplates.entrySet()) {
            if (e.getValue().equals(predicted.get(e.getKey()))) same++;
            else log("  town centre MISMATCH " + e.getKey() + ": real " + e.getValue() + ", predicted " + predicted.get(e.getKey()));
            if (e.getValue().contains("/zombie/")) zombiesReal++;
        }
        log(String.format("town centres: %d/%d predicted exactly; zombie villages real %d, predicted %d", same, realTemplates.size(), zombiesReal, zombiesPredicted));
        // a handful, like a player would have
        List<SeedMath.Obs> few = new ArrayList<>(obs.subList(0, Math.min(8, obs.size())));
        try {
            long t0 = System.nanoTime();
            SeedMath.Search s = new SeedMath.Search(few, null, 4, () -> false);
            List<Long> low = s.run();
            log(String.format("structure seed from %d starts: %s %s (%d candidates, %.1fs)", few.size(),
                low.contains(SEED & SeedMath.MASK) ? "OK" : "MISS", s.refused == null ? "" : s.refused, low.size(), (System.nanoTime() - t0) / 1e9));
            long hashed = mc.level.getBiomeManager().biomeZoomSeed;
            log("client hashed seed matches: " + (hashed == BiomeManager.obfuscateSeed(SEED)));
        } catch (InterruptedException e) {
            log("interrupted");
        }
    }

    private static void scanHere(String where) {
        int r = mc.options.getEffectiveRenderDistance();
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        Map<String, Integer> composites = new TreeMap<>();
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            for (StructureMatcher.Find f : StructureMatcher.scan(mc.level, chunk).finds()) {
                if (!f.exact()) { composites.merge(f.category(), 1, Integer::sum); continue; }
                String key = f.id() + "@" + f.start().x() + "," + f.start().z();
                if (!seenExact.add(key)) continue;
                // villages: the template's folder (plains...) names the village type
                boolean ok = real.contains(key) || real.stream().anyMatch(rr -> rr.endsWith("@" + f.start().x() + "," + f.start().z()) && rr.startsWith("village") && f.id().startsWith("village"));
                if (ok) tp++; else { fp++; log("  FALSE exact find " + key + " at " + f.pos() + ": " + StructureMatcher.debug(mc.level, f.id(), f.start())); }
            }
        }
        log(where + ": exact so far " + tp + " real / " + fp + " false, composites here " + composites);
    }

    private static void baitTest(MinecraftServer server) {
        BlockPos p = mc.player.blockPosition();
        int x = p.getX() + 3, z = p.getZ() + 3, y = p.getY() - 30;
        String[] blocks = {"tripwire_hook", "bell", "sea_lantern", "crying_obsidian", "blue_terracotta", "nether_brick_fence", "purpur_pillar",
            "gilded_blackstone", "chiseled_stone_bricks", "sticky_piston", "potted_red_mushroom", "cauldron", "tnt", "orange_terracotta"};
        for (int i = 0; i < blocks.length; i++) cmd(server, "setblock " + (x + i) + " " + y + " " + z + " minecraft:" + blocks[i]);
        seenExact.clear();
    }

    private static void biomeTest() {
        var check = StructurePredictor.BiomeCheck.of(Level.OVERWORLD);
        List<StructurePredictor.BiomeSample> samples = new ArrayList<>();
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        for (int x = cx - 6; x <= cx + 6; x += 2) for (int z = cz - 6; z <= cz + 6; z += 2) {
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            int qx = (x << 2) + 2, qz = (z << 2) + 2;
            chunk.getNoiseBiome(qx, 16, qz).unwrapKey().ifPresent(k -> samples.add(new StructurePredictor.BiomeSample(qx, 16, qz, k.identifier().toString())));
        }
        long t0 = System.nanoTime();
        int right = check.mismatches(SEED, samples, samples.size());
        double ms = (System.nanoTime() - t0) / 1e6;
        int wrongTop = 0;
        for (long hi = 1; hi <= 20; hi++) if (check.mismatches((hi << 48) | (SEED & SeedMath.MASK), samples, 2) <= 2) wrongTop++;
        log(String.format("biomes: %d samples, real seed %d wrong (%.0f ms), %d of 20 wrong top bits pass", samples.size(), right, ms, wrongTop));
        t0 = System.nanoTime();
        for (long hi = 100; hi < 150; hi++) check.mismatches((hi << 48) | (SEED & SeedMath.MASK), samples, 2);
        log(String.format("biome check speed: %.1f ms per candidate (full 65536 sweep ~%.0f s on 1 thread)",
            (System.nanoTime() - t0) / 1e6 / 50, (System.nanoTime() - t0) / 1e9 / 50 * 65536));
    }
}
