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
import dev.prismglass.xray.StructurePredictor;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds structures two ways:
 * <ul>
 *   <li><b>Seed</b>: predicts structure starts from the world seed (same seed as Xray, {@code .seed}) with
 *       {@link StructurePredictor}, in chunks you've never loaded. Vanilla 26.1 generation; old/custom terrain differs.</li>
 *   <li><b>Loaded</b>: recognises structures in loaded chunks by blocks only they contain (end portal frames,
 *       bells, trial spawners, gilded blackstone...). Works on any server and any terrain age, no seed needed.</li>
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
    private final List<Marker> announcedMarkers = new ArrayList<>();
    private final List<Label> labelList = new ArrayList<>();

    public StructureFinder() { super("StructureFinder", "Finds structures (from the seed, or recognised in loaded chunks).", Category.WORLD); }

    @Override
    public void onEnable() {
        found.clear();
        queue.clear();
        scanned.clear();
        predicted = List.of();
        scanKey = "";
    }

    @Override
    public void onWorldJoin() {
        onEnable();
        announcedMarkers.clear();
        predictor = null;
    }

    private boolean wants(String category) { return structures.items().contains(category); }

    // ---- tick ------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
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
                    out.add(new Marker(h.category(), h.pos(), true));
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
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        int r = Math.max(2, mc.options.getEffectiveRenderDistance());
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
            List<Marker> hits = scanChunk(chunk);
            if (hits.isEmpty()) continue;
            found.put(key, hits);
            for (Marker m : hits) announce(m);
        }
        // forget chunks far away so a later visit re-checks them (blocks may have changed)
        found.keySet().removeIf(k -> far(k, cx, cz, r + 8));
        scanned.removeIf(k -> far(k, cx, cz, r + 8));
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

    // ---- loaded-chunk signatures -------------------------------------------------------------------------------

    /** Blocks that only (or practically only) generate inside one structure; min = how many make it convincing. */
    private record Signature(String category, int min, int priority) {}

    private static final Map<Block, Signature> SIGNATURES = new IdentityHashMap<>();

    static {
        SIGNATURES.put(Blocks.END_PORTAL_FRAME, new Signature("stronghold", 1, 9));
        SIGNATURES.put(Blocks.TRIAL_SPAWNER, new Signature("trial_chambers", 1, 9));
        SIGNATURES.put(Blocks.VAULT, new Signature("trial_chambers", 1, 9));
        SIGNATURES.put(Blocks.REINFORCED_DEEPSLATE, new Signature("ancient_city", 1, 9));
        SIGNATURES.put(Blocks.GILDED_BLACKSTONE, new Signature("bastion", 1, 9));
        SIGNATURES.put(Blocks.END_GATEWAY, new Signature("end_gateway", 1, 9));
        SIGNATURES.put(Blocks.BELL, new Signature("village", 1, 8));
        SIGNATURES.put(Blocks.SEA_LANTERN, new Signature("monument", 4, 8));
        SIGNATURES.put(Blocks.NETHER_BRICK_FENCE, new Signature("fortress", 3, 8));
        SIGNATURES.put(Blocks.PURPUR_PILLAR, new Signature("end_city", 3, 8));
        SIGNATURES.put(Blocks.PURPUR_BLOCK, new Signature("end_city", 8, 8));
        SIGNATURES.put(Blocks.BLUE_TERRACOTTA, new Signature("desert_pyramid", 1, 7));
        SIGNATURES.put(Blocks.TRIPWIRE_HOOK, new Signature("jungle_temple", 1, 6));
        SIGNATURES.put(Blocks.CRYING_OBSIDIAN, new Signature("ruined_portal", 1, 5));
        SIGNATURES.put(Blocks.SPAWNER, new Signature("spawner", 1, 4));
    }

    private List<Marker> scanChunk(LevelChunk chunk) {
        Map<String, int[]> counts = new HashMap<>();
        Map<String, BlockPos> first = new HashMap<>();
        Map<String, Integer> priority = new HashMap<>();
        int ox = chunk.getPos().x() << 4, oz = chunk.getPos().z() << 4;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> SIGNATURES.containsKey(st.getBlock()))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                Signature sig = SIGNATURES.get(s.getBlockState(bx, by, bz).getBlock());
                if (sig == null || !wants(sig.category())) continue;
                counts.computeIfAbsent(sig.category(), k -> new int[1])[0]++;
                first.putIfAbsent(sig.category(), new BlockPos(ox + bx, baseY + by, oz + bz));
                priority.put(sig.category(), sig.priority());
            }
        }
        // bastions contain crying obsidian too: the strongest signature in a chunk wins
        int best = counts.keySet().stream().filter(c -> counts.get(c)[0] >= minFor(c)).mapToInt(priority::get).max().orElse(-1);
        List<Marker> out = new ArrayList<>();
        for (String c : counts.keySet()) {
            if (counts.get(c)[0] >= minFor(c) && priority.get(c) == best) out.add(new Marker(c, first.get(c), false));
        }
        return out;
    }

    private static int minFor(String category) {
        int min = Integer.MAX_VALUE;
        for (Signature s : SIGNATURES.values()) if (s.category().equals(category)) min = Math.min(min, s.min());
        return min;
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
            case "village" -> 96;
            default -> 24;
        };
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        labelList.clear();
        double maxSq = sq(renderDistance.get());
        Vec3 eye = mc.player.getEyePosition(delta);
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

    private static String nice(String category) {
        String s = category.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int colorOf(String category) {
        return switch (category) {
            case "village" -> 0xFF8BE36B;
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
