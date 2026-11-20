package dev.prismglass.xray;

import dev.prismglass.Prism;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Recognises structures in loaded chunks, no seed needed. Two kinds of evidence:
 * <ul>
 *   <li><b>Exact</b> (jungle temple, desert pyramid, swamp hut, village centre): the whole vanilla layout has to be
 *       there, built by vanilla's own piece code ({@link PieceRecorder}) or read from its template, in one of its
 *       orientations, starting exactly on a chunk corner like real ones do (structure starts sit at their chunk's
 *       minimum corner). That also gives the exact start chunk, which is what the seedcracker needs.</li>
 *   <li><b>Composite</b> (everything else): blocks players can't get in survival (end portal frames, trial
 *       spawners, reinforced deepslate...) or several structure-only blocks together, in the right dimension.</li>
 * </ul>
 */
public final class StructureMatcher {
    /** A found structure; {@code start} is its exact start chunk (exact matches only, else null). */
    public record Find(String category, String id, BlockPos pos, ChunkPos start) {
        public boolean exact() { return start != null; }
    }

    /** {@code retry}: part of a candidate layout is in unloaded chunks, or the layouts aren't built yet. */
    public record Result(List<Find> finds, boolean retry) {}

    /** Share of a layout's blocks that must be right (allows for looting, griefing, a broken trap). */
    private static final double MATCH = 0.7;
    /** Share of a layout that must be in loaded chunks before deciding either way. */
    private static final double COVERAGE = 0.9;

    private static final class Print {
        final String category, id;
        final int[] dx, dy, dz;
        final Block[] blocks;
        final Map<Block, int[]> anchors = new IdentityHashMap<>();
        final int cx, cy, cz;

        Print(String category, String id, List<int[]> offsets, List<Block> blocks) {
            this.category = category;
            this.id = id;
            int n = offsets.size();
            dx = new int[n]; dy = new int[n]; dz = new int[n];
            this.blocks = blocks.toArray(new Block[0]);
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = x0, y1 = x1, z0 = x0, z1 = x1;
            Map<Block, List<Integer>> where = new IdentityHashMap<>();
            for (int i = 0; i < n; i++) {
                int[] o = offsets.get(i);
                dx[i] = o[0]; dy[i] = o[1]; dz[i] = o[2];
                x0 = Math.min(x0, o[0]); x1 = Math.max(x1, o[0]);
                y0 = Math.min(y0, o[1]); y1 = Math.max(y1, o[1]);
                z0 = Math.min(z0, o[2]); z1 = Math.max(z1, o[2]);
                where.computeIfAbsent(this.blocks[i], k -> new ArrayList<>()).add(i);
            }
            cx = (x0 + x1) / 2; cy = y1; cz = (z0 + z1) / 2;
            // anchors: the layout's rarer blocks (a world block is only a candidate if the chunk-corner rule fits)
            for (var e : where.entrySet()) {
                if (e.getValue().size() > 12 || COMMON.contains(e.getKey())) continue;
                anchors.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
            }
        }
    }

    /** Ground blocks: left out of template layouts entirely. */
    private static final Set<Block> TERRAIN = Set.of(Blocks.DIRT, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.STONE, Blocks.SNOW_BLOCK,
        Blocks.ICE, Blocks.PACKED_ICE, Blocks.CLAY, Blocks.DEEPSLATE);
    /** A village centre needs at least this many built blocks to be told apart from a village street. */
    private static final int MIN_VILLAGE_BLOCKS = 24;

    /** Blocks too common in the world to start a layout check from. */
    private static final Set<Block> COMMON = Set.of(Blocks.STONE, Blocks.DIRT, Blocks.SAND, Blocks.SANDSTONE, Blocks.GRAVEL,
        Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.CLAY, Blocks.OAK_LOG, Blocks.SPRUCE_LOG,
        Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.TERRACOTTA, Blocks.RED_SAND);

    private static volatile List<Print> prints;
    private static volatile Map<Block, List<Print>> byAnchor = Map.of();
    private static volatile boolean preparing;

    private StructureMatcher() {}

    public static boolean ready() { return prints != null; }

    /** Builds the layouts on a background thread (vanilla piece code + templates from the jar, about a second). */
    public static synchronized void prepare() {
        if (prints != null || preparing) return;
        preparing = true;
        Thread t = new Thread(() -> {
            try {
                List<Print> out = new ArrayList<>();
                addPiece(out, "jungle_temple", "jungle_pyramid", r -> new JungleTemplePiece(r, 0, 0));
                addPiece(out, "desert_pyramid", "desert_pyramid", r -> new DesertPyramidPiece(r, 0, 0));
                addPiece(out, "swamp_hut", "swamp_hut", r -> {
                    SwampHutPiece p = new SwampHutPiece(r, 0, 0);
                    // the recording level has no entities to spawn
                    p.spawnedWitch = true;
                    p.spawnedCat = true;
                    return p;
                });
                addVillages(out);
                Map<Block, List<Print>> index = new IdentityHashMap<>();
                for (Print p : out) for (Block b : p.anchors.keySet()) index.computeIfAbsent(b, k -> new ArrayList<>()).add(p);
                byAnchor = index;
                prints = out;
                Prism.LOG.info("[structures] {} exact layouts ready", out.size());
            } catch (Throwable e) {
                Prism.LOG.error("[structures] could not build structure layouts", e);
                prints = List.of();
            }
        }, "Prism StructureMatcher");
        t.setDaemon(true);
        t.start();
    }

    /** Records a scattered-feature piece in all four orientations, relative to its box corner. */
    private static void addPiece(List<Print> out, String category, String id, Function<RandomSource, StructurePiece> make) {
        Set<Direction> seen = EnumSet.noneOf(Direction.class);
        // one running random: fresh LCGs from small seeds all give the same first nextInt(4), i.e. the same direction
        RandomSource random = RandomSource.create(0x5DEECE66DL);
        for (int tries = 0; seen.size() < 4 && tries < 256; tries++) {
            StructurePiece piece = make.apply(random);
            if (!seen.add(piece.getOrientation())) continue;
            // a known ground height skips the heightmap probe (and keeps the box at y 64)
            ((ScatteredFeaturePiece) piece).heightPosition = 0;
            int ox = piece.getBoundingBox().minX(), oy = piece.getBoundingBox().minY(), oz = piece.getBoundingBox().minZ();
            List<int[]> offsets = new ArrayList<>();
            List<Block> blocks = new ArrayList<>();
            for (var e : PieceRecorder.record(piece).entrySet()) {
                Block b = comparable(e.getValue());
                if (b == null) continue;
                BlockPos p = e.getKey();
                // the buried foundation is sand/sandstone like the desert floor itself: it would match plain ground
                if (p.getY() - oy <= 2 && (TERRAIN.contains(b) || b == Blocks.SANDSTONE)) continue;
                offsets.add(new int[]{p.getX() - ox, p.getY() - oy, p.getZ() - oz});
                blocks.add(b);
            }
            out.add(new Print(category, id, offsets, blocks));
            if (Boolean.getBoolean("prismglass.cracktest")) {
                StringBuilder hooks = new StringBuilder();
                for (int i = 0; i < blocks.size(); i++) if (blocks.get(i) == Blocks.TRIPWIRE_HOOK)
                    hooks.append(Arrays.toString(offsets.get(i)));
                Print pr = out.get(out.size() - 1);
                Prism.LOG.info("[structures] layout {} #{} facing {} box {} marks {} x {}..{} z {}..{} n {}", id, out.size() - 1, piece.getOrientation(), piece.getBoundingBox(), hooks,
                    Arrays.stream(pr.dx).min().getAsInt(), Arrays.stream(pr.dx).max().getAsInt(), Arrays.stream(pr.dz).min().getAsInt(), Arrays.stream(pr.dz).max().getAsInt(), pr.blocks.length);
            }
        }
    }

    /** Village centres: the start piece, its template origin on the start chunk's corner, any of 4 rotations. */
    private static void addVillages(List<Print> out) {
        var templates = StructurePredictor.loadTemplates("village", p -> p.contains("/town_centers/"));
        for (var e : templates.entrySet()) {
            String path = e.getKey().getPath(); // village/plains/zombie/town_centers/plains_meeting_point_1
            String type = path.split("/")[1];
            StructureTemplate template = e.getValue();
            if (template.palettes.isEmpty()) continue;
            var infos = template.palettes.get(0).blocks();
            for (Rotation rotation : Rotation.values()) {
                List<int[]> offsets = new ArrayList<>();
                List<Block> blocks = new ArrayList<>();
                for (var info : infos) {
                    Block b = comparable(info.state());
                    // paths, sand and stone are the terrain a village sits on (streets are full of them): only the build counts
                    if (b == null || TERRAIN.contains(b)) continue;
                    BlockPos p = StructureTemplate.transform(info.pos(), Mirror.NONE, rotation, BlockPos.ZERO);
                    offsets.add(new int[]{p.getX(), p.getY(), p.getZ()});
                    blocks.add(b);
                }
                // abandoned villages: their own (zombie) town centres; the best-matching layout decides which it is
                String category = path.contains("/zombie/") ? "zombie_village" : "village";
                if (offsets.size() >= MIN_VILLAGE_BLOCKS) out.add(new Print(category, "village_" + type, offsets, blocks));
                else if (rotation == Rotation.NONE) Prism.LOG.debug("[structures] {} too plain to match ({} blocks)", path, offsets.size());
            }
        }
    }

    /**
     * The block a layout position is compared as, or null to skip it: air, liquids, plants, snow, leaves and the
     * structure's own random variants (moss on cobblestone, cracked bricks, suspicious sand) are folded or left out.
     */
    private static Block comparable(BlockState s) {
        Block b = s.getBlock();
        if (s.isAir() || b instanceof LiquidBlock || b == Blocks.STRUCTURE_VOID || b == Blocks.JIGSAW || b == Blocks.STRUCTURE_BLOCK
            || b instanceof VineBlock || b instanceof LeavesBlock || b instanceof CropBlock || b == Blocks.SNOW || b == Blocks.COBWEB
            || s.canBeReplaced()) return null;
        return canonical(b);
    }

    private static Block canonical(Block b) {
        if (b == Blocks.MOSSY_COBBLESTONE) return Blocks.COBBLESTONE;
        if (b == Blocks.MOSSY_COBBLESTONE_STAIRS) return Blocks.COBBLESTONE_STAIRS;
        if (b == Blocks.MOSSY_COBBLESTONE_SLAB) return Blocks.COBBLESTONE_SLAB;
        if (b == Blocks.MOSSY_COBBLESTONE_WALL) return Blocks.COBBLESTONE_WALL;
        if (b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.CRACKED_STONE_BRICKS) return Blocks.STONE_BRICKS;
        if (b == Blocks.MOSSY_STONE_BRICK_STAIRS) return Blocks.STONE_BRICK_STAIRS;
        if (b == Blocks.MOSSY_STONE_BRICK_SLAB) return Blocks.STONE_BRICK_SLAB;
        if (b == Blocks.MOSSY_STONE_BRICK_WALL) return Blocks.STONE_BRICK_WALL;
        if (b == Blocks.SUSPICIOUS_SAND) return Blocks.SAND;
        if (b == Blocks.SUSPICIOUS_GRAVEL) return Blocks.GRAVEL;
        if (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT_PATH || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT || b == Blocks.PODZOL
            || b == Blocks.MYCELIUM || b == Blocks.FARMLAND) return Blocks.DIRT;
        return b;
    }

    // ---- scanning ----------------------------------------------------------------------------------------------

    private record Cached(long time, Result result) {}
    private static final Map<LevelChunk, Cached> CACHE = new WeakHashMap<>();

    /** {@link #scan}, remembered per chunk for a while (StructureFinder and SeedCracker both ask). */
    public static Result scanCached(Level level, LevelChunk chunk) {
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(chunk);
        if (c != null && now - c.time() < 20_000) return c.result();
        Result r = scan(level, chunk);
        if (!r.retry()) CACHE.put(chunk, new Cached(now, r));
        return r;
    }

    public static Result scan(Level level, LevelChunk chunk) {
        prepare();
        Map<Block, int[]> counts = new IdentityHashMap<>();
        Map<Block, BlockPos> first = new IdentityHashMap<>();
        List<BlockPos> anchorPos = new ArrayList<>();
        List<Block> anchorBlock = new ArrayList<>();
        Map<Block, List<Print>> anchors = byAnchor;
        int ox = chunk.getPos().x() << 4, oz = chunk.getPos().z() << 4;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> TRACKED.contains(st.getBlock()) || anchors.containsKey(canonical(st.getBlock())))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                Block b = s.getBlockState(bx, by, bz).getBlock();
                if (TRACKED.contains(b)) {
                    counts.computeIfAbsent(b, k -> new int[1])[0]++;
                    first.putIfAbsent(b, new BlockPos(ox + bx, baseY + by, oz + bz));
                }
                Block c = canonical(b);
                if (anchors.containsKey(c)) {
                    anchorPos.add(new BlockPos(ox + bx, baseY + by, oz + bz));
                    anchorBlock.add(c);
                }
            }
        }
        List<Find> finds = new ArrayList<>();
        boolean retry = !ready();
        // exact layouts
        record Tried(Print print, long pos) {}
        Set<Tried> tried = new HashSet<>();
        // per start (and kind, zombie villages counting as villages): the layout that matched best
        Map<String, Object[]> best = new LinkedHashMap<>();
        for (int a = 0; a < anchorPos.size(); a++) {
            BlockPos p = anchorPos.get(a);
            for (Print print : anchors.getOrDefault(anchorBlock.get(a), List.of())) {
                for (int i : print.anchors.get(anchorBlock.get(a))) {
                    int x = p.getX() - print.dx[i], y = p.getY() - print.dy[i], z = p.getZ() - print.dz[i];
                    if ((x & 15) != 0 || (z & 15) != 0) continue; // starts sit on a chunk corner
                    if (!tried.add(new Tried(print, BlockPos.asLong(x, y, z)))) continue;
                    double verdict = check(level, print, x, y, z);
                    if (verdict == 0) retry = true;
                    if (verdict > 0) {
                        Find f = new Find(print.category, print.id, new BlockPos(x + print.cx, y + print.cy, z + print.cz), new ChunkPos(x >> 4, z >> 4));
                        String key = f.start().x() + "," + f.start().z() + "|" + f.category().replace("zombie_", "");
                        Object[] prev = best.get(key);
                        if (prev == null || (double) prev[1] < verdict) best.put(key, new Object[]{f, verdict});
                    }
                }
            }
        }
        for (Object[] b : best.values()) finds.add((Find) b[0]);
        dedupeExact(finds);
        finds.addAll(composite(level.dimension(), counts, first));
        return new Result(finds, retry);
    }

    /** The share of the layout that's right when it's there (> 0), -1 when it isn't, 0 when too little is loaded to tell. */
    private static double check(Level level, Print p, int x, int y, int z) {
        int n = p.blocks.length, allowedBad = (int) Math.floor(n * (1 - MATCH));
        int ok = 0, bad = 0, missing = 0;
        long lastKey = Long.MIN_VALUE;
        LevelChunk chunk = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            int wx = x + p.dx[i], wy = y + p.dy[i], wz = z + p.dz[i];
            long key = ChunkPos.pack(wx >> 4, wz >> 4);
            if (key != lastKey) {
                lastKey = key;
                chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
            }
            if (chunk == null || level.isOutsideBuildHeight(wy)) { missing++; continue; }
            if (canonical(chunk.getBlockState(pos.set(wx, wy, wz)).getBlock()) == p.blocks[i]) ok++;
            else if (++bad > allowedBad) return -1;
        }
        if (ok + bad < n * COVERAGE) return 0;
        return ok >= (ok + bad) * MATCH ? (double) ok / (ok + bad) : -1;
    }

    /** Dev: the best score of any layout for this structure id at this start chunk (every height). */
    public static String debug(Level level, String idPrefix, ChunkPos start) {
        String best = "no layout";
        double bestShare = -1;
        List<Print> all = prints == null ? List.of() : prints;
        for (int pi = 0; pi < all.size(); pi++) {
            Print p = all.get(pi);
            if (!p.id.startsWith(idPrefix)) continue;
            for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                int ok = 0, bad = 0, missing = 0;
                Map<Block, Integer> wrong = new HashMap<>();
                for (int i = 0; i < p.blocks.length; i++) {
                    BlockPos w = new BlockPos(start.getMinBlockX() + p.dx[i], y + p.dy[i], start.getMinBlockZ() + p.dz[i]);
                    if (level.getChunkSource().getChunkNow(w.getX() >> 4, w.getZ() >> 4) == null) { missing++; continue; }
                    if (canonical(level.getBlockState(w).getBlock()) == p.blocks[i]) ok++;
                    else { bad++; wrong.merge(p.blocks[i], 1, Integer::sum); }
                }
                double share = ok / (double) Math.max(1, ok + bad);
                if (share > bestShare) {
                    bestShare = share;
                    var top = wrong.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(5)
                        .map(e -> e.getKey().getDescriptionId().replace("block.minecraft.", "") + "=" + e.getValue()).toList();
                    best = String.format("%s #%d y=%d ok=%d bad=%d missing=%d of %d (%.0f%%) wrong %s anchors %s", p.id, pi, y, ok, bad, missing,
                        p.blocks.length, share * 100, top, p.anchors.keySet().stream().map(b -> b.getDescriptionId().replace("block.minecraft.", "")).toList());
                }
            }
        }
        return best;
    }

    /** The same start can match from several anchors (and a zombie village as its plain twin): one find each. */
    private static void dedupeExact(List<Find> finds) {
        List<Find> out = new ArrayList<>();
        for (Find f : finds) {
            boolean dup = false;
            for (Find o : out) if (o.category().equals(f.category()) && o.start().equals(f.start())) { dup = true; break; }
            if (!dup) out.add(f);
        }
        finds.clear();
        finds.addAll(out);
    }

    // ---- composite rules ---------------------------------------------------------------------------------------

    private static final Set<Block> TRACKED = Set.of(Blocks.END_PORTAL_FRAME, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.REINFORCED_DEEPSLATE,
        Blocks.END_GATEWAY, Blocks.SPAWNER, Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
        Blocks.SEA_LANTERN, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICKS,
        Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_STAIRS, Blocks.END_STONE_BRICKS, Blocks.END_ROD, Blocks.CRYING_OBSIDIAN,
        Blocks.OBSIDIAN, Blocks.NETHERRACK, Blocks.MAGMA_BLOCK, Blocks.GOLD_BLOCK);

    private static int n(Map<Block, int[]> c, Block... blocks) {
        int t = 0;
        for (Block b : blocks) { int[] v = c.get(b); if (v != null) t += v[0]; }
        return t;
    }

    /**
     * Rules that need several structure-only signs at once, so one placed block (a player's bell, sea lantern,
     * tripwire hook, crying obsidian...) never counts. The strongest one in a chunk wins: bastions contain crying
     * obsidian, fortresses spawners.
     */
    private static List<Find> composite(ResourceKey<Level> dim, Map<Block, int[]> c, Map<Block, BlockPos> first) {
        boolean over = dim == Level.OVERWORLD, nether = dim == Level.NETHER, end = dim == Level.END;
        List<Object[]> hits = new ArrayList<>(); // category, block, priority
        if (over && n(c, Blocks.END_PORTAL_FRAME) >= 1) hits.add(new Object[]{"stronghold", Blocks.END_PORTAL_FRAME, 9});
        if (over && n(c, Blocks.TRIAL_SPAWNER, Blocks.VAULT) >= 1) hits.add(new Object[]{"trial_chambers", n(c, Blocks.TRIAL_SPAWNER) > 0 ? Blocks.TRIAL_SPAWNER : Blocks.VAULT, 9});
        if (over && n(c, Blocks.REINFORCED_DEEPSLATE) >= 1) hits.add(new Object[]{"ancient_city", Blocks.REINFORCED_DEEPSLATE, 9});
        if (end && n(c, Blocks.END_GATEWAY) >= 1) hits.add(new Object[]{"end_gateway", Blocks.END_GATEWAY, 9});
        if (nether && n(c, Blocks.GILDED_BLACKSTONE) >= 1 && n(c, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) >= 24)
            hits.add(new Object[]{"bastion", Blocks.GILDED_BLACKSTONE, 9});
        if (over && n(c, Blocks.SEA_LANTERN) >= 1 && n(c, Blocks.DARK_PRISMARINE) >= 2 && n(c, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE) >= 64)
            hits.add(new Object[]{"monument", Blocks.SEA_LANTERN, 8});
        if (nether && n(c, Blocks.NETHER_BRICK_FENCE) >= 3 && n(c, Blocks.NETHER_BRICKS) >= 48)
            hits.add(new Object[]{"fortress", Blocks.NETHER_BRICK_FENCE, 8});
        if (end && n(c, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR) >= 16 && n(c, Blocks.END_STONE_BRICKS) >= 8 && (n(c, Blocks.PURPUR_STAIRS) >= 2 || n(c, Blocks.END_ROD) >= 1))
            hits.add(new Object[]{"end_city", n(c, Blocks.PURPUR_PILLAR) > 0 ? Blocks.PURPUR_PILLAR : Blocks.PURPUR_BLOCK, 8});
        if (!end && n(c, Blocks.CRYING_OBSIDIAN) >= 1 && n(c, Blocks.OBSIDIAN) >= 3 && n(c, Blocks.NETHERRACK, Blocks.MAGMA_BLOCK, Blocks.GOLD_BLOCK) >= 3)
            hits.add(new Object[]{"ruined_portal", Blocks.CRYING_OBSIDIAN, 5});
        if (n(c, Blocks.SPAWNER) >= 1) hits.add(new Object[]{"spawner", Blocks.SPAWNER, 4});
        int best = -1;
        for (Object[] h : hits) best = Math.max(best, (int) h[2]);
        List<Find> out = new ArrayList<>();
        for (Object[] h : hits) if ((int) h[2] == best) out.add(new Find((String) h[0], (String) h[0], first.get((Block) h[1]), null));
        return out;
    }
}
