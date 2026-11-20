package dev.prismglass.module.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.module.render.Xray;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import dev.prismglass.xray.StructureMatcher;
import dev.prismglass.xray.StructurePredictor;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds structures two ways:
 * <ul>
 *   <li><b>Seed</b>: predicts structure starts from the world seed (same seed as Xray, {@code .seed}) with
 *       {@link StructurePredictor}, in chunks you've never loaded. Vanilla 26.1 generation; old/custom terrain differs.</li>
 *   <li><b>Loaded</b>: recognises structures in loaded chunks ({@link StructureMatcher}): jungle temples, desert
 *       pyramids, swamp huts and village centres only when their whole vanilla layout is there on a chunk corner,
 *       the rest from blocks players can't place or several structure-only blocks together. Works on any server
 *       and any terrain age, no seed needed; the exact finds also feed the SeedCracker.</li>
 * </ul>
 * Predicted markers are hollow; a loaded find of the same kind nearby confirms them.
 */
public class StructureFinder extends Module {
    private static final String[] ALL = {"village", "stronghold", "monument", "mansion", "desert_pyramid", "jungle_temple",
        "swamp_hut", "igloo", "outpost", "ancient_city", "trial_chambers", "trail_ruins", "shipwreck", "ocean_ruin",
        "ruined_portal", "buried_treasure", "mineshaft", "nether_fossil", "fortress", "bastion", "end_city", "end_gateway", "spawner"};

    public final ModeSetting mode = mode("Mode", "Both", "Seed = predicted from the world seed (.seed). Loaded = recognised in chunks you have loaded.",
        "Both", "Seed", "Loaded");
    public final ListSetting structures = choices("Structures",
        "village,stronghold,monument,mansion,desert_pyramid,jungle_temple,swamp_hut,igloo,outpost,ancient_city,trial_chambers,trail_ruins,shipwreck,ruined_portal,buried_treasure,fortress,bastion,end_city,end_gateway",
        "Which structures to show.", false, 0, ALL)
        .icons("village", "bell", "stronghold", "end_portal_frame", "monument", "prismarine_bricks", "mansion", "dark_oak_log",
            "desert_pyramid", "chiseled_sandstone", "jungle_temple", "mossy_cobblestone", "swamp_hut", "cauldron", "igloo", "snow_block",
            "outpost", "crossbow", "ancient_city", "sculk_catalyst", "trial_chambers", "trial_key", "trail_ruins", "brush",
            "shipwreck", "oak_boat", "ocean_ruin", "heart_of_the_sea", "ruined_portal", "crying_obsidian", "buried_treasure", "chest",
            "mineshaft", "rail", "nether_fossil", "bone_block", "fortress", "nether_bricks", "bastion", "gilded_blackstone",
            "end_city", "purpur_block", "end_gateway", "ender_pearl", "spawner", "spawner");
    public final NumberSetting radius = num("Radius", 64, 8, 256, 8, "Seed mode: chunks around you to predict.");
    public final NumberSetting renderDistance = num("RenderDistance", 3000, 100, 20000, 100, "Hide markers farther than this (blocks).");
    public final BoolSetting labels = bool("Labels", true, "Name and distance on screen.");
    public final BoolSetting tracers = bool("Tracers", false, "Lines to structures.");
    public final BoolSetting notify = bool("Notify", true, "Message when a structure is found in loaded chunks.");

    private record Marker(String category, BlockPos pos, boolean predicted) {}
    /** The structure {@code .structurefinder find} picked: shown until you're {@link #REACHED} blocks from it. */
    private record Target(String name, BlockPos pos, ResourceKey<Level> dim) {}
    private static final int REACHED = 50;
    private volatile Target target;
    private volatile boolean finding;
    private record Label(String text, int color, double x, double y) {}

    // seed mode: predicted on a worker thread
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Prism StructureFinder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile List<Marker> predicted = List.of();
    private volatile boolean busy;
    private StructurePredictor predictor;
    private String scanKey = "";
    private ChunkPos scanCenter;

    // loaded mode: incremental chunk scan, like Xray
    private static final long TICK_BUDGET_NS = 1_500_000;
    private final Map<Long, List<Marker>> found = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Set<Long> scanned = new HashSet<>();
    /** Chunks to look at again: part of a candidate structure was still unloaded (chunk -> tick to retry at). */
    private final Map<Long, Integer> retryAt = new HashMap<>();
    private int ticks;
    private final List<Marker> announcedMarkers = new ArrayList<>();
    private final List<Label> labelList = new ArrayList<>();

    public StructureFinder() { super("StructureFinder", "Finds structures (from the seed, or recognised in loaded chunks).", Category.WORLD); }

    @Override
    public void onEnable() {
        found.clear();
        queue.clear();
        scanned.clear();
        retryAt.clear();
        predicted = List.of();
        scanKey = "";
    }

    @Override
    public void onWorldJoin() {
        target = null;
        onEnable();
        announcedMarkers.clear();
        predictor = null;
    }

    /** (zombie villages are villages for the Structures list) */
    private boolean wants(String category) { return structures.items().contains(category.equals("zombie_village") ? "village" : category); }

    // ---- tick ------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        Target t = target;
        if (t != null && t.dim() == mc.level.dimension() && Math.sqrt(horizontal(mc.player.blockPosition(), t.pos())) <= REACHED) {
            target = null;
            ChatUtil.good("StructureFinder: reached the " + t.name() + ", marker removed");
        }
        if (!mode.is("Loaded")) tickSeed();
        if (!mode.is("Seed")) tickLoaded();
    }

    private void tickSeed() {
        String seedText = Prism.modules().get(Xray.class).seed.get().trim();
        if (seedText.isEmpty() || busy) return;
        long seed = Xray.parseSeed(seedText);
        ResourceKey<Level> dim = mc.level.dimension();
        ChunkPos here = mc.player.chunkPosition();
        int r = radius.getInt();
        String key = dim.identifier() + "|" + seed + "|" + r + "|" + structures.get();
        boolean moved = scanCenter == null || Math.max(Math.abs(here.x() - scanCenter.x()), Math.abs(here.z() - scanCenter.z())) > r / 4;
        if (key.equals(scanKey) && !moved) return;
        scanKey = key;
        scanCenter = here;
        Set<String> want = new HashSet<>(structures.items());
        busy = true;
        WORKER.execute(() -> {
            try {
                if (predictor == null || !predictor.isFor(dim, seed)) predictor = StructurePredictor.create(dim, seed);
                if (predictor == null) { predicted = List.of(); return; }
                List<Marker> out = new ArrayList<>();
                for (StructurePredictor.Hit h : predictor.scan(here.x(), here.z(), r, want::contains)) {
                    out.add(new Marker(h.zombieVillage() ? "zombie_village" : h.category(), h.pos(), true));
                }
                predicted = out;
            } catch (Throwable t) {
                Prism.LOG.error("[structures] prediction failed", t);
            } finally {
                busy = false;
            }
        });
    }

    private void tickLoaded() {
        ticks++;
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        int r = Math.max(2, mc.options.getEffectiveRenderDistance());
        for (var it = retryAt.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() > ticks) continue;
            it.remove();
            scanned.remove(e.getKey());
        }
        if (queue.isEmpty()) {
            for (int d = 0; d <= r; d++) {
                for (int x = cx - d; x <= cx + d; x++) for (int z = cz - d; z <= cz + d; z++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != d) continue;
                    long key = ChunkPos.pack(x, z);
                    if (!scanned.contains(key)) queue.add(key);
                }
            }
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            long key = queue.poll();
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            scanned.add(key);
            StructureMatcher.Result result = StructureMatcher.scanCached(mc.level, chunk);
            if (result.retry()) retryAt.put(key, ticks + 40);
            List<Marker> hits = new ArrayList<>();
            for (StructureMatcher.Find f : result.finds()) if (wants(f.category())) hits.add(new Marker(f.category(), f.pos(), false));
            if (hits.isEmpty()) continue;
            found.put(key, hits);
            for (Marker m : hits) announce(m);
        }
        // forget chunks far away so a later visit re-checks them (blocks may have changed)
        found.keySet().removeIf(k -> far(k, cx, cz, r + 8));
        scanned.removeIf(k -> far(k, cx, cz, r + 8));
        retryAt.keySet().removeIf(k -> far(k, cx, cz, r + 8));
    }

    private static boolean far(long key, int cx, int cz, int r) {
        return Math.max(Math.abs(ChunkPos.getX(key) - cx), Math.abs(ChunkPos.getZ(key) - cz)) > r;
    }

    private void announce(Marker m) {
        if (!notify.get()) return;
        // one message per structure: big ones (trial chambers, fortresses) show up in several chunks
        for (Marker o : announcedMarkers) {
            if (o.category().equals(m.category()) && horizontal(o.pos(), m.pos()) < sq(mergeRadius(m.category()))) return;
        }
        announcedMarkers.add(m);
        ChatUtil.info("Found " + nice(m.category()) + " at " + m.pos().getX() + ", " + m.pos().getY() + ", " + m.pos().getZ()
            + " (" + Math.round(Math.sqrt(mc.player.blockPosition().distSqr(m.pos()))) + "m)");
    }

    // ---- render ------------------------------------------------------------------------------------------------

    /** Loaded finds, merged so one big structure (fortress, city) isn't a marker per chunk; plus predictions. */
    private List<Marker> markers() {
        List<Marker> out = new ArrayList<>();
        for (List<Marker> list : found.values()) {
            for (Marker m : list) {
                boolean dup = false;
                for (Marker o : out) if (o.category().equals(m.category()) && o.pos().distSqr(m.pos()) < sq(mergeRadius(m.category()))) { dup = true; break; }
                if (!dup) out.add(m);
            }
        }
        List<Marker> loaded = new ArrayList<>(out);
        for (Marker p : predicted) {
            if (!wants(p.category())) continue;
            boolean confirmed = false;
            for (Marker l : loaded) if (l.category().equals(p.category()) && horizontal(l.pos(), p.pos()) < sq(mergeRadius(p.category()) + 32)) { confirmed = true; break; }
            if (!confirmed) out.add(p);
        }
        return out;
    }

    private static double sq(double v) { return v * v; }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static int mergeRadius(String category) {
        return switch (category) {
            case "fortress", "ancient_city", "stronghold" -> 112;
            case "end_city", "bastion", "monument", "mansion", "trial_chambers" -> 64;
            case "village", "zombie_village" -> 96;
            default -> 24;
        };
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        labelList.clear();
        double maxSq = sq(renderDistance.get());
        Vec3 eye = mc.player.getEyePosition(delta);
        Target t = target;
        if (t != null && t.dim() == mc.level.dimension()) {
            Vec3 c = Vec3.atBottomCenterOf(t.pos());
            double dist = Math.sqrt(horizontal(mc.player.blockPosition(), t.pos()));
            // far away it's drawn 200 blocks out in its direction (the real spot is past the far plane)
            Vec3 shown = c;
            if (dist > 200) {
                Vec3 dir = new Vec3(c.x - eye.x, 0, c.z - eye.z).normalize().scale(200);
                shown = new Vec3(eye.x + dir.x, c.y, eye.z + dir.z);
            }
            int col = 0xFFFFD84A;
            Render3D.box(new AABB(shown.x - 0.4, shown.y, shown.z - 0.4, shown.x + 0.4, shown.y + 160, shown.z + 0.4), ColorUtil.withAlpha(col, 70), ColorUtil.withAlpha(col, 255), true);
            Render3D.tracer(shown.add(0, 1, 0), ColorUtil.withAlpha(col, 200));
            double[] sc = Render3D.project(shown.add(0, 3, 0));
            if (sc != null) labelList.add(new Label("\u00a7l" + t.name() + "\u00a7r \u00a77" + Math.round(dist) + "m (" + t.pos().getX() + ", " + t.pos().getZ() + ")", col, sc[0], sc[1]));
        }
        for (Marker m : markers()) {
            Vec3 c = Vec3.atBottomCenterOf(m.pos());
            double dSq = eye.distanceToSqr(c);
            if (dSq > maxSq) continue;
            int col = colorOf(m.category());
            AABB beam = new AABB(c.x - 0.15, m.pos().getY(), c.z - 0.15, c.x + 0.15, m.pos().getY() + 48, c.z + 0.15);
            Render3D.box(beam, m.predicted() ? 0 : ColorUtil.withAlpha(col, 60), ColorUtil.withAlpha(col, m.predicted() ? 140 : 230), true);
            Render3D.box(new AABB(m.pos()).inflate(0.5), ColorUtil.withAlpha(col, m.predicted() ? 20 : 50), ColorUtil.withAlpha(col, 200), true);
            if (tracers.get()) Render3D.tracer(c.add(0, 1, 0), ColorUtil.withAlpha(col, 120));
            if (labels.get()) {
                double[] s = Render3D.project(c.add(0, 2.5, 0));
                if (s != null) {
                    String text = nice(m.category()) + (m.predicted() ? " §7(seed)" : "") + " §7" + Math.round(Math.sqrt(dSq)) + "m";
                    labelList.add(new Label(text, col, s[0], s[1]));
                }
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (labelList.isEmpty()) return;
        Glass.Style style = Prism.modules().get(ClickGui.class).panelStyle();
        for (Label l : labelList) {
            float w = mc.font.width(l.text()) + 14;
            float x = (float) l.x() - w / 2, y = (float) l.y() - 13;
            Glass.panel(ctx, x, y, w, 13, style);
            ctx.fill((int) x + 4, (int) y + 4, (int) x + 7, (int) y + 9, l.color());
            ctx.text(mc.font, l.text(), (int) x + 10, (int) y + 3, 0xFFFFFFFF, true);
        }
    }

    // ---- .structurefinder find -----------------------------------------------------------------------------------

    /** Friendlier names for the village types (by their main wood) and a few common words. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("oak_village", "village_plains"), Map.entry("plains_village", "village_plains"),
        Map.entry("desert_village", "village_desert"), Map.entry("sandstone_village", "village_desert"),
        Map.entry("savanna_village", "village_savanna"), Map.entry("acacia_village", "village_savanna"),
        Map.entry("snowy_village", "village_snowy"), Map.entry("snow_village", "village_snowy"), Map.entry("ice_village", "village_snowy"),
        Map.entry("taiga_village", "village_taiga"), Map.entry("spruce_village", "village_taiga"),
        Map.entry("temple", "jungle_temple"), Map.entry("jungle_pyramid", "jungle_temple"), Map.entry("pyramid", "desert_pyramid"),
        Map.entry("witch_hut", "swamp_hut"), Map.entry("pillager_outpost", "outpost"), Map.entry("ocean_monument", "monument"),
        Map.entry("woodland_mansion", "mansion"), Map.entry("nether_fortress", "fortress"), Map.entry("bastion_remnant", "bastion"),
        Map.entry("trial_chamber", "trial_chambers"), Map.entry("portal", "ruined_portal"), Map.entry("treasure", "buried_treasure"),
        Map.entry("abandoned_village", "zombie_village"));

    /** Everything {@code find} takes, for tab completion: kinds, exact vanilla ids (village_plains...) and aliases. */
    public static List<String> findNames() {
        TreeSet<String> names = new TreeSet<>(Arrays.asList(ALL));
        names.remove("spawner");
        names.remove("end_gateway");
        names.addAll(ALIASES.keySet());
        names.add("zombie_village");
        names.addAll(StructurePredictor.structureIds());
        for (String b : StructurePredictor.biomeIds()) names.add("biome_" + b);
        return new ArrayList<>(names);
    }

    /** Looks for the nearest predicted structure of that kind (or exact id) on the worker and marks it. */
    public void find(String name) {
        if (name.toLowerCase(Locale.ROOT).startsWith("biome_")) { findBiome(name.substring(6).toLowerCase(Locale.ROOT)); return; }
        String query = ALIASES.getOrDefault(name.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
        boolean zombie = query.equals("zombie_village");
        boolean isCategory = zombie || Arrays.asList(ALL).contains(query) && !query.equals("spawner") && !query.equals("end_gateway");
        if (!isCategory && !StructurePredictor.structureIds().contains(query)) { ChatUtil.error("Unknown structure " + name + " (tab shows them)"); return; }
        String seedText = Prism.modules().get(Xray.class).seed.get().trim();
        if (seedText.isEmpty()) { ChatUtil.error("Needs the world seed: .seed <seed>, or crack it with SeedCracker"); return; }
        if (finding) { ChatUtil.error("Still looking, hang on"); return; }
        if (!isEnabled()) toggle();
        long seed = Xray.parseSeed(seedText);
        ResourceKey<Level> dim = mc.level.dimension();
        BlockPos from = mc.player.blockPosition();
        String category = zombie ? "village" : isCategory ? query : StructurePredictor.category(query);
        // what counts: the kind, an exact id, or (zombie_village) a village whose town centre is a zombie one
        java.util.function.Predicate<StructurePredictor.Hit> match = zombie ? StructurePredictor.Hit::zombieVillage
            : isCategory ? h -> h.category().equals(query) : h -> h.id().equals(query);
        String label = nice(query);
        finding = true;
        ChatUtil.info("StructureFinder: looking for the nearest " + label + "...");
        WORKER.execute(() -> {
            try {
                if (predictor == null || !predictor.isFor(dim, seed)) predictor = StructurePredictor.create(dim, seed);
                if (predictor == null) { mc.execute(() -> ChatUtil.error("No structure generation to predict in this dimension")); return; }
                StructurePredictor.Hit best = null;
                int cx = from.getX() >> 4, cz = from.getZ() >> 4;
                for (int r = 32; r <= 1024 && best == null; r *= 2) best = nearest(predictor.scan(cx, cz, r, category::equals), match, from);
                // the square can hide a closer one just past its edge: widen to the found distance once
                if (best != null) {
                    int need = (int) Math.ceil(Math.sqrt(horizontal(from, best.pos())) / 16) + 1;
                    StructurePredictor.Hit closer = nearest(predictor.scan(cx, cz, need, category::equals), match, from);
                    if (closer != null) best = closer;
                }
                StructurePredictor.Hit found = best;
                mc.execute(() -> {
                    if (found == null) { ChatUtil.error("No " + label + " within 16k blocks (wrong dimension?)"); return; }
                    target = new Target(label, found.pos(), dim);
                    ChatUtil.good(String.format("StructureFinder: nearest %s at %d, %d (%dm), marked until you're %dm away",
                        label, found.pos().getX(), found.pos().getZ(), Math.round(Math.sqrt(horizontal(mc.player.blockPosition(), found.pos()))), REACHED));
                });
            } catch (Throwable e) {
                Prism.LOG.error("[structures] find failed", e);
                mc.execute(() -> ChatUtil.error("StructureFinder: search failed (" + e.getMessage() + ")"));
            } finally {
                finding = false;
            }
        });
    }

    /** {@code .sf find biome_<id>}: the nearest spot of that biome from the seed, marked like a structure. */
    private void findBiome(String biome) {
        if (!StructurePredictor.biomeIds().isEmpty() && !StructurePredictor.biomeIds().contains(biome)) { ChatUtil.error("Unknown biome " + biome + " (tab shows them)"); return; }
        String seedText = Prism.modules().get(Xray.class).seed.get().trim();
        if (seedText.isEmpty()) { ChatUtil.error("Needs the world seed: .seed <seed>, or crack it with SeedCracker"); return; }
        if (finding) { ChatUtil.error("Still looking, hang on"); return; }
        if (!isEnabled()) toggle();
        long seed = Xray.parseSeed(seedText);
        ResourceKey<Level> dim = mc.level.dimension();
        BlockPos from = mc.player.blockPosition();
        String label = nice(biome);
        // cave biomes are thin: look at more heights
        boolean cave = biome.contains("cave") || biome.equals("deep_dark");
        finding = true;
        ChatUtil.info("StructureFinder: looking for the nearest " + label + "...");
        WORKER.execute(() -> {
            try {
                if (predictor == null || !predictor.isFor(dim, seed)) predictor = StructurePredictor.create(dim, seed);
                if (predictor == null) { mc.execute(() -> ChatUtil.error("No biome generation to predict in this dimension")); return; }
                if (!predictor.hasBiome(biome)) { mc.execute(() -> ChatUtil.error(label + " doesn't generate in this dimension")); return; }
                BlockPos found = predictor.findBiome(biome, from, 6400, 32, cave ? 32 : 64);
                mc.execute(() -> {
                    if (found == null) { ChatUtil.error("No " + label + " within 6400 blocks"); return; }
                    target = new Target(label, found, dim);
                    ChatUtil.good(String.format("StructureFinder: nearest %s at %d, %d, %d (%dm), marked until you're %dm away",
                        label, found.getX(), found.getY(), found.getZ(), Math.round(Math.sqrt(horizontal(mc.player.blockPosition(), found))), REACHED));
                });
            } catch (Throwable e) {
                Prism.LOG.error("[structures] biome find failed", e);
                mc.execute(() -> ChatUtil.error("StructureFinder: biome search failed (" + e.getMessage() + ")"));
            } finally {
                finding = false;
            }
        });
    }

    private static StructurePredictor.Hit nearest(List<StructurePredictor.Hit> hits, java.util.function.Predicate<StructurePredictor.Hit> match, BlockPos from) {
        StructurePredictor.Hit best = null;
        for (StructurePredictor.Hit h : hits) {
            if (!match.test(h)) continue;
            if (best == null || horizontal(from, h.pos()) < horizontal(from, best.pos())) best = h;
        }
        return best;
    }

    /** Dev: where the find marker is (null when none). */
    public BlockPos targetPos() { Target t = target; return t == null ? null : t.pos(); }

    public void stopFind() {
        if (target == null) { ChatUtil.info("No structure marked"); return; }
        target = null;
        ChatUtil.good("StructureFinder: marker removed");
    }

    private static String nice(String category) {
        String s = category.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int colorOf(String category) {
        return switch (category) {
            case "village" -> 0xFF8BE36B;
            case "zombie_village" -> 0xFF6E8B3D;
            case "stronghold" -> 0xFF7A5CFF;
            case "monument" -> 0xFF46D8C8;
            case "mansion" -> 0xFF9C6B3C;
            case "desert_pyramid" -> 0xFFF2D27A;
            case "jungle_temple" -> 0xFF4CAF50;
            case "ancient_city" -> 0xFF2EC4E8;
            case "trial_chambers" -> 0xFFE88A2E;
            case "ruined_portal" -> 0xFFB23CFF;
            case "fortress" -> 0xFFE04848;
            case "bastion" -> 0xFFFFC23C;
            case "end_city" -> 0xFFE7A6FF;
            case "end_gateway" -> 0xFFFFFFFF;
            case "spawner" -> 0xFFFF5A8C;
            case "outpost" -> 0xFFB0B0B0;
            case "shipwreck", "ocean_ruin" -> 0xFF4A8DFF;
            default -> 0xFFC8D0DC;
        };
    }

    @Override
    public String getInfo() {
        int n = predicted.size();
        for (List<Marker> l : found.values()) n += l.size();
        if (!mode.is("Loaded") && Prism.modules().get(Xray.class).seed.get().isBlank()) return "no seed";
        return busy ? "..." : String.valueOf(n);
    }
}
