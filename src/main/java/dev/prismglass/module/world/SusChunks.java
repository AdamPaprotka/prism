package dev.prismglass.module.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sus chunks: amethyst geodes that have grown out, which only happens where a player kept the area loaded for hours.
 *
 * <p>Budding amethyst grows on random ticks, and random ticks only run within simulation distance of a player.
 * A freshly generated geode has a few buds of every size on its budding blocks and most open faces empty (about 9% of
 * the open faces hold a full cluster). Left loaded long enough, every open face of every budding block ends up as a
 * full cluster. So per chunk: Growth = clusters / open faces of budding amethyst. A grown chunk means someone stayed
 * within simulation distance of it - a base, an AFK farm, a stash spot.
 *
 * <p>Area: the player who grew it stood within SimDistance chunks, so the overlap of those squares around a group of
 * grown chunks is where they were. Palette mode: a chunk with full clusters but no smaller bud anywhere counts as grown.
 *
 * <p>Light mode, the anti-xray bypass: anti-xray rewrites the blocks in chunk packets but sends the block light as the
 * server computed it. Amethyst clusters are the only block in the game that emits light 5, large buds the only one
 * that emits 4, and a lit spot that no neighbour outshines is a light source of exactly its own level. So the light
 * data alone counts clusters and large buds (fresh geodes ~1:1, grown ones all clusters) without trusting a single
 * block. Auto uses Blocks, Light on Folia servers, and Light whenever a chunk has cluster light but no visible budding
 * amethyst (hidden or scrambled).
 *
 * <p>Kelp: kelp generates with its top at age 20-23 and only reaches 25 by growing on random ticks (about 10-40
 * minutes inside simulation distance). Ocean chunks whose kelp tops are mostly age 25 were kept loaded. Tops that hit
 * the surface stop growing early, so they are left out.
 */
public class SusChunks extends Module {
    public final NumberSetting growth = num("Growth", 60, 20, 100, 1, "Percent of open budding faces holding a full cluster to flag a chunk (fresh geodes ~9%).");
    public final NumberSetting minFaces = num("MinFaces", 6, 1, 60, 1, "Open budding faces a chunk needs before it's judged.");
    public final ModeSetting method = mode("Method", "Auto", "Blocks = count every face; Light = count clusters/large buds from the block-light data (anti-xray bypass); Palette = clusters but no buds in the chunk; Auto = Blocks, Light on Folia and wherever the blocks look hidden.", "Auto", "Blocks", "Light", "Palette");
    public final NumberSetting lightGrowth = num("LightGrowth", 85, 50, 100, 1, "Light method: percent of light sources that are full clusters (fresh ~50%).").visibleWhen(() -> !method.is("Blocks") && !method.is("Palette"));
    public final BoolSetting kelp = bool("Kelp", true, "Also flag ocean chunks whose kelp tops grew to the max age (only by staying loaded ~30 min).");
    public final NumberSetting kelpGrowth = num("KelpGrowth", 60, 10, 100, 1, "Percent of kelp tops at max age to flag (fresh chunks: 0%).").visibleWhen(kelp::get);
    public final NumberSetting minKelp = num("MinKelp", 5, 1, 60, 1, "Kelp tops (below the surface) a chunk needs before it's judged.").visibleWhen(kelp::get);
    public final ColorSetting kelpColor = color("KelpColor", 0xFF3CE0B0, "Kelp chunk colour.").visibleWhen(kelp::get);
    public final BoolSetting beams = bool("Beams", true, "Light beam up from flagged chunks, visible from far away.");
    public final BoolSetting area = bool("Area", true, "Mark where the player who grew it must have been (overlap of simulation-distance squares).");
    public final NumberSetting simDistance = num("SimDistance", 8, 2, 32, 1, "The server's simulation distance in chunks (vanilla 10).").visibleWhen(area::get);
    public final BoolSetting boxes = bool("Boxes", true, "Box around the geode part of flagged chunks.");
    public final BoolSetting tracers = bool("Tracers", false, "Lines to flagged chunks.");
    public final BoolSetting labels = bool("Labels", true, "Growth % over flagged chunks.");
    public final BoolSetting announce = bool("Announce", true, "Chat message for each new sus chunk.");
    public final ColorSetting color = color("Color", 0xFFB46CFF, "Flagged chunk colour.");
    public final NumberSetting budget = num("BudgetMs", 2, 0.5, 10, 0.5, "Max scan time per tick (ms).");

    /**
     * One scanned chunk: open faces of budding amethyst and what grows on them. open = -1 for a palette verdict;
     * light = true when the counts are light sources (open = clusters + large buds).
     */
    public record Result(int x, int z, int open, int clusters, int buds, int minY, int maxY, boolean light) {
        public Result(int x, int z, int open, int clusters, int buds, int minY, int maxY) { this(x, z, open, clusters, buds, minY, maxY, false); }
        public float growth() { return open <= 0 ? (open < 0 ? 1f : 0f) : clusters / (float) open; }
    }

    /** Kelp in one chunk: tops at max age vs tops still under water that could grow. */
    public record KelpResult(int x, int z, int grown, int fresh, int minY, int maxY) {
        public float growth() { return grown + fresh == 0 ? 0 : grown / (float) (grown + fresh); }
    }

    /** A rectangle of chunks (inclusive) where the player who grew a group of geodes stood. */
    public record Area(int x0, int z0, int x1, int z1, int chunks) {}

    private final Map<Long, Result> results = new HashMap<>();
    private final Map<Long, KelpResult> kelpResults = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> dirtySince = new ConcurrentHashMap<>();
    private final LinkedHashSet<Long> queue = new LinkedHashSet<>();
    private List<Area> areas = List.of();
    private boolean areasDirty;
    private ResourceKey<Level> dimension;

    public SusChunks() {
        super("SusChunks", "Finds chunks where amethyst has grown out: someone stayed near there for hours.", Category.WORLD);
    }

    @Override public void onEnable() { reset(); enqueueLoaded(); }
    @Override public void onWorldJoin() { reset(); }

    private void reset() {
        results.clear(); kelpResults.clear(); announced.clear(); dirty.clear(); dirtySince.clear(); queue.clear();
        areas = List.of();
        areasDirty = false;
        dimension = null;
    }

    private void enqueueLoaded() {
        if (mc.player == null) return;
        int r = mc.options.renderDistance().get() + 1;
        ChunkPos c = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) queue.add(ChunkPos.pack(c.x() + x, c.z() + z));
    }

    @Override
    public String getInfo() {
        long n = results.values().stream().filter(this::flagged).count() + kelpResults.values().stream().filter(this::flagged).count();
        return n == 0 ? null : String.valueOf(n);
    }

    public boolean flagged(Result r) {
        if (r.open() < 0) return true;
        return r.open() >= minFaces.getInt() && r.growth() * 100 >= (r.light() ? lightGrowth.get() : growth.get());
    }

    public boolean flagged(KelpResult k) {
        return kelp.get() && k.grown() + k.fresh() >= minKelp.getInt() && k.growth() * 100 >= kelpGrowth.get();
    }

    public Map<Long, Result> results() { return results; }
    public Map<Long, KelpResult> kelpResults() { return kelpResults; }
    public List<Area> areas() { return areas; }

    // ---- packets (netty thread: only touch the concurrent collections) -----------------------------

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (event.packet instanceof ClientboundLevelChunkWithLightPacket p) {
            mark(ChunkPos.pack(p.x(), p.z()));
        } else if (event.packet instanceof ClientboundBlockUpdatePacket p) {
            if (amethyst(p.getBlockState())) mark(ChunkPos.pack(p.getPos().getX() >> 4, p.getPos().getZ() >> 4));
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            p.runUpdates((pos, state) -> { if (amethyst(state)) mark(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4)); });
        }
    }

    private void mark(long key) {
        dirtySince.putIfAbsent(key, System.currentTimeMillis());
        dirty.add(key);
    }

    private static boolean amethyst(BlockState s) {
        return s.is(Blocks.BUDDING_AMETHYST) || s.is(Blocks.AMETHYST_CLUSTER) || bud(s) || s.is(Blocks.KELP);
    }

    private static boolean bud(BlockState s) {
        return s.is(Blocks.SMALL_AMETHYST_BUD) || s.is(Blocks.MEDIUM_AMETHYST_BUD) || s.is(Blocks.LARGE_AMETHYST_BUD);
    }

    // ---- scanning ------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (dimension != mc.level.dimension()) {
            reset();
            dimension = mc.level.dimension();
            enqueueLoaded();
        }
        long now = System.currentTimeMillis();
        for (Iterator<Long> it = dirty.iterator(); it.hasNext(); ) {
            long key = it.next();
            Long since = dirtySince.get(key);
            if (since != null && now - since < 500) continue; // let the chunk data / burst of updates land first
            dirtySince.remove(key);
            queue.add(key);
            it.remove();
        }
        long deadline = System.nanoTime() + (long) (budget.get() * 1_000_000);
        Iterator<Long> it = queue.iterator();
        while (it.hasNext() && System.nanoTime() < deadline) {
            long key = it.next();
            it.remove();
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) scan(key, chunk);
        }
        if (areasDirty) { areasDirty = false; areas = computeAreas(); }
    }

    private boolean folia() {
        if (mc.getConnection() == null) return false;
        String brand = mc.getConnection().serverBrand();
        return brand != null && brand.toLowerCase(Locale.ROOT).contains("folia");
    }

    private void scan(long key, LevelChunk chunk) {
        scanKelp(key, chunk);
        if (method.is("Light") || (method.is("Auto") && folia())) { store(key, scanLight(key, chunk)); return; }
        Result r = scanBlocks(key, chunk);
        if (method.is("Auto") && (r == null || r.open() < minFaces.getInt())) {
            // nothing (or too little) to see in the blocks: if the light says clusters are there, they're hidden
            Result l = scanLight(key, chunk);
            if (l != null && l.clusters() >= minFaces.getInt()) r = l;
        }
        store(key, r);
    }

    /**
     * Light sources of level 5 (clusters) and 4 (large buds) in the chunk's block light. A spot is a source of its own
     * level when no neighbour is brighter (light only spreads by losing 1 per block).
     */
    private Result scanLight(long key, LevelChunk chunk) {
        LayerLightEventListener layer = mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        int clusters = 0, large = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (int i = 0; i < chunk.getSections().length; i++) {
            int sy = chunk.getSectionYFromSectionIndex(i);
            DataLayer d = layer.getDataLayerData(SectionPos.of(cx, sy, cz));
            if (d == null || d.isEmpty()) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int v = d.get(x, y, z);
                if (v != 5 && v != 4) continue;
                boolean source = true;
                for (Direction dir : Direction.values()) {
                    int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
                    int nv = nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16 ? d.get(nx, ny, nz)
                        : layer.getLightValue(n.set((cx << 4) + nx, (sy << 4) + ny, (cz << 4) + nz));
                    if (nv > v) { source = false; break; }
                }
                if (!source) continue;
                if (v == 5) clusters++; else large++;
                minY = Math.min(minY, (sy << 4) + y - 1);
                maxY = Math.max(maxY, (sy << 4) + y + 1);
            }
        }
        return clusters + large == 0 ? null : new Result(cx, cz, clusters + large, clusters, large, minY, maxY, true);
    }

    /** Kelp tops: max age (grown) vs younger ones with water above (could still grow). */
    private void scanKelp(long key, LevelChunk chunk) {
        if (!kelp.get()) { kelpResults.remove(key); return; }
        LevelChunkSection[] sections = chunk.getSections();
        int grown = 0, fresh = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int baseX = ChunkPos.getX(key) << 4, baseZ = ChunkPos.getZ(key) << 4;
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.KELP))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState s = section.getBlockState(x, y, z);
                if (!s.is(Blocks.KELP)) continue;
                int wy = baseY + y;
                if (s.getValue(BlockStateProperties.AGE_25) >= 25) grown++;
                else if (mc.level.getBlockState(above.set(baseX + x, wy + 1, baseZ + z)).is(Blocks.WATER)) fresh++;
                else continue; // reached the surface: stops growing early, says nothing
                minY = Math.min(minY, wy);
                maxY = Math.max(maxY, wy);
            }
        }
        KelpResult r = grown + fresh == 0 ? null : new KelpResult(ChunkPos.getX(key), ChunkPos.getZ(key), grown, fresh, minY, maxY);
        KelpResult old = r == null ? kelpResults.remove(key) : kelpResults.put(key, r);
        if (old != null || r != null) areasDirty = true;
        if (r != null && flagged(r) && announced.add(key ^ 0x4B454C50L) && announce.get())
            ChatUtil.good("SusChunks: kelp " + Math.round(r.growth() * 100) + "% grown (" + (r.grown() + r.fresh()) + " tops) at §f" + (r.x() * 16 + 8) + ", " + (r.z() * 16 + 8));
    }

    private Result scanBlocks(long key, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        boolean palette = method.is("Palette");
        int open = 0, clusters = 0, buds = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        boolean sawCluster = false, sawBud = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), n = new BlockPos.MutableBlockPos();
        int baseX = ChunkPos.getX(key) << 4, baseZ = ChunkPos.getZ(key) << 4;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            if (palette) {
                boolean c = section.maybeHas(s -> s.is(Blocks.AMETHYST_CLUSTER)), b = section.maybeHas(SusChunks::bud);
                if (c || b) { minY = Math.min(minY, baseY); maxY = Math.max(maxY, baseY + 15); }
                sawCluster |= c;
                sawBud |= b;
                continue;
            }
            if (!section.maybeHas(s -> s.is(Blocks.BUDDING_AMETHYST))) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!section.getBlockState(x, y, z).is(Blocks.BUDDING_AMETHYST)) continue;
                p.set(baseX + x, baseY + y, baseZ + z);
                boolean any = false;
                for (Direction d : Direction.values()) {
                    BlockState s = mc.level.getBlockState(n.setWithOffset(p, d));
                    boolean facingOut = s.hasProperty(BlockStateProperties.FACING) && s.getValue(BlockStateProperties.FACING) == d;
                    if (s.is(Blocks.AMETHYST_CLUSTER) && facingOut) { open++; clusters++; any = true; }
                    else if (bud(s) && facingOut) { open++; buds++; any = true; }
                    else if (s.isAir() || s.is(Blocks.WATER)) { open++; any = true; }
                }
                if (any) { minY = Math.min(minY, p.getY() - 1); maxY = Math.max(maxY, p.getY() + 1); }
            }
        }
        if (palette) return sawCluster || sawBud ? new Result(ChunkPos.getX(key), ChunkPos.getZ(key), sawCluster && !sawBud ? -1 : 0, 0, 0, minY, maxY) : null;
        return open > 0 ? new Result(ChunkPos.getX(key), ChunkPos.getZ(key), open, clusters, buds, minY, maxY) : null;
    }

    private void store(long key, Result r) {
        Result old = r == null ? results.remove(key) : results.put(key, r);
        if (old != null || r != null) areasDirty = true;
        if (r != null && flagged(r) && announced.add(key) && announce.get()) {
            String g = r.open() < 0 ? "fully grown (palette)" : Math.round(r.growth() * 100) + "% grown, " + r.open()
                + (r.light() ? " light sources (light)" : " faces");
            ChatUtil.good("SusChunks: amethyst " + g + " at §f" + (r.x() * 16 + 8) + ", " + (r.z() * 16 + 8));
        }
    }

    /**
     * Groups flagged chunks that could share one player (within 2 x SimDistance of each other) and intersects their
     * simulation-distance squares. An empty overlap means more than one player grew them: that group gets no area.
     */
    private List<Area> computeAreas() {
        List<Result> flagged = new ArrayList<>();
        for (Result r : results.values()) if (flagged(r)) flagged.add(r);
        for (KelpResult k : kelpResults.values()) if (flagged(k)) flagged.add(new Result(k.x(), k.z(), -1, 0, 0, k.minY(), k.maxY()));
        int s = simDistance.getInt();
        List<Area> out = new ArrayList<>();
        boolean[] used = new boolean[flagged.size()];
        for (int i = 0; i < flagged.size(); i++) {
            if (used[i]) continue;
            List<Result> group = new ArrayList<>(List.of(flagged.get(i)));
            used[i] = true;
            for (int g = 0; g < group.size(); g++)
                for (int j = 0; j < flagged.size(); j++)
                    if (!used[j] && Math.abs(flagged.get(j).x() - group.get(g).x()) <= 2 * s && Math.abs(flagged.get(j).z() - group.get(g).z()) <= 2 * s) {
                        used[j] = true;
                        group.add(flagged.get(j));
                    }
            int x0 = Integer.MIN_VALUE, z0 = Integer.MIN_VALUE, x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE;
            for (Result r : group) {
                x0 = Math.max(x0, r.x() - s); x1 = Math.min(x1, r.x() + s);
                z0 = Math.max(z0, r.z() - s); z1 = Math.min(z1, r.z() + s);
            }
            if (x0 <= x1 && z0 <= z1) out.add(new Area(x0, z0, x1, z1, group.size()));
        }
        return out;
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        int view = (mc.options.renderDistance().get() + 2) * 16;
        Vec3 me = mc.player.position();
        int c = color.color();
        for (Result r : results.values()) {
            if (!flagged(r)) continue;
            double cx = r.x() * 16 + 8, cz = r.z() * 16 + 8;
            if (Math.abs(cx - me.x) > view || Math.abs(cz - me.z) > view) continue;
            int shade = ColorUtil.lerp(ColorUtil.withAlpha(c, 255), 0xFFFF3B5C, Mth.clamp((r.growth() - 0.6f) / 0.4f, 0f, 1f));
            if (boxes.get())
                Render3D.box(new AABB(r.x() * 16, r.minY(), r.z() * 16, r.x() * 16 + 16, r.maxY() + 1, r.z() * 16 + 16), ColorUtil.withAlpha(shade, 30), ColorUtil.withAlpha(shade, 220), true);
            if (tracers.get()) Render3D.tracer(new Vec3(cx, (r.minY() + r.maxY()) / 2.0, cz), ColorUtil.withAlpha(shade, 170));
            if (beams.get()) beam(cx, r.maxY() + 1, cz, shade);
        }
        int kc = kelpColor.color();
        for (KelpResult k : kelpResults.values()) {
            if (!flagged(k)) continue;
            double cx = k.x() * 16 + 8, cz = k.z() * 16 + 8;
            if (Math.abs(cx - me.x) > view || Math.abs(cz - me.z) > view) continue;
            if (boxes.get())
                Render3D.box(new AABB(k.x() * 16, k.minY(), k.z() * 16, k.x() * 16 + 16, k.maxY() + 1, k.z() * 16 + 16), ColorUtil.withAlpha(kc, 26), ColorUtil.withAlpha(kc, 210), true);
            if (tracers.get()) Render3D.tracer(new Vec3(cx, k.maxY(), cz), ColorUtil.withAlpha(kc, 170));
            if (beams.get()) beam(cx, k.maxY() + 1, cz, kc);
        }
        if (area.get()) {
            double y = Math.floor(me.y) - 0.98;
            for (Area a : areas) {
                AABB box = new AABB(a.x0() * 16, y, a.z0() * 16, (a.x1() + 1) * 16, y + 0.05, (a.z1() + 1) * 16);
                Render3D.box(box, ColorUtil.withAlpha(c, 24), ColorUtil.withAlpha(c, 200), true);
            }
        }
    }

    /** A slowly breathing beam from y up to the build limit, thin core inside a faint glow. */
    private void beam(double x, double y, double z, int color) {
        float pulse = 0.7f + 0.3f * Mth.sin(System.currentTimeMillis() / 400f + (float) (x * 0.13 + z * 0.07));
        double top = mc.level.getMaxY() + 1;
        Render3D.box(new AABB(x - 0.18, y, z - 0.18, x + 0.18, top, z + 0.18), ColorUtil.withAlpha(color, (int) (90 * pulse)), ColorUtil.withAlpha(color, (int) (120 * pulse)), true);
        Render3D.box(new AABB(x - 0.6, y, z - 0.6, x + 0.6, top, z + 0.6), ColorUtil.withAlpha(color, (int) (22 * pulse)), 0, true);
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (!labels.get()) return;
        Vec3 me = mc.player.position();
        for (Result r : results.values()) {
            if (!flagged(r)) continue;
            double cx = r.x() * 16 + 8, cz = r.z() * 16 + 8;
            if (Math.abs(cx - me.x) > 160 || Math.abs(cz - me.z) > 160) continue;
            double[] s = Render3D.project(new Vec3(cx, r.maxY() + 1.5, cz));
            if (s == null) continue;
            String text = r.open() < 0 ? "grown amethyst" : Math.round(r.growth() * 100) + "% grown" + (r.light() ? " §7(light)" : "");
            ctx.text(mc.font, text, (int) s[0] - mc.font.width(text) / 2, (int) s[1], color.color(), true);
        }
        for (KelpResult k : kelpResults.values()) {
            if (!flagged(k)) continue;
            double cx = k.x() * 16 + 8, cz = k.z() * 16 + 8;
            if (Math.abs(cx - me.x) > 160 || Math.abs(cz - me.z) > 160) continue;
            double[] s = Render3D.project(new Vec3(cx, k.maxY() + 1.5, cz));
            if (s == null) continue;
            String text = "kelp " + Math.round(k.growth() * 100) + "% grown";
            ctx.text(mc.font, text, (int) s[0] - mc.font.width(text) / 2, (int) s[1], kelpColor.color(), true);
        }
    }
}
