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
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
 * grown chunks is where they were. Palette mode (Auto on servers whose brand says Folia, where anti-xray can scramble
 * single blocks): a chunk with full clusters but no smaller bud anywhere counts as grown.
 */
public class SusChunks extends Module {
    public final NumberSetting growth = num("Growth", 60, 20, 100, 1, "Percent of open budding faces holding a full cluster to flag a chunk (fresh geodes ~9%).");
    public final NumberSetting minFaces = num("MinFaces", 6, 1, 60, 1, "Open budding faces a chunk needs before it's judged.");
    public final ModeSetting method = mode("Method", "Auto", "Blocks = count every face; Palette = clusters but no buds in the chunk (anti-xray servers); Auto = Palette on Folia servers.", "Auto", "Blocks", "Palette");
    public final BoolSetting area = bool("Area", true, "Mark where the player who grew it must have been (overlap of simulation-distance squares).");
    public final NumberSetting simDistance = num("SimDistance", 8, 2, 32, 1, "The server's simulation distance in chunks (vanilla 10).").visibleWhen(area::get);
    public final BoolSetting boxes = bool("Boxes", true, "Box around the geode part of flagged chunks.");
    public final BoolSetting tracers = bool("Tracers", false, "Lines to flagged chunks.");
    public final BoolSetting labels = bool("Labels", true, "Growth % over flagged chunks.");
    public final BoolSetting announce = bool("Announce", true, "Chat message for each new sus chunk.");
    public final ColorSetting color = color("Color", 0xFFB46CFF, "Flagged chunk colour.");
    public final NumberSetting budget = num("BudgetMs", 2, 0.5, 10, 0.5, "Max scan time per tick (ms).");

    /** One scanned chunk: open faces of budding amethyst and what grows on them. open = -1 for a palette verdict. */
    public record Result(int x, int z, int open, int clusters, int buds, int minY, int maxY) {
        public float growth() { return open <= 0 ? (open < 0 ? 1f : 0f) : clusters / (float) open; }
    }

    /** A rectangle of chunks (inclusive) where the player who grew a group of geodes stood. */
    public record Area(int x0, int z0, int x1, int z1, int chunks) {}

    private final Map<Long, Result> results = new HashMap<>();
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
        results.clear(); announced.clear(); dirty.clear(); dirtySince.clear(); queue.clear();
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
        long n = results.values().stream().filter(this::flagged).count();
        return n == 0 ? null : String.valueOf(n);
    }

    public boolean flagged(Result r) {
        return r.open() < 0 || (r.open() >= minFaces.getInt() && r.growth() * 100 >= growth.get());
    }

    public Map<Long, Result> results() { return results; }
    public List<Area> areas() { return areas; }

    // ---- packets (netty thread: only touch the concurrent collections) -----------------------------

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (event.packet instanceof ClientboundLevelChunkWithLightPacket p) {
            mark(ChunkPos.pack(p.getX(), p.getZ()));
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
        return s.is(Blocks.BUDDING_AMETHYST) || s.is(Blocks.AMETHYST_CLUSTER) || bud(s);
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

    private boolean paletteMode() {
        if (method.is("Palette")) return true;
        if (!method.is("Auto") || mc.getConnection() == null) return false;
        String brand = mc.getConnection().serverBrand();
        return brand != null && brand.toLowerCase(Locale.ROOT).contains("folia");
    }

    private void scan(long key, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        boolean palette = paletteMode();
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
        Result r;
        if (palette) r = sawCluster || sawBud ? new Result(ChunkPos.getX(key), ChunkPos.getZ(key), sawCluster && !sawBud ? -1 : 0, 0, 0, minY, maxY) : null;
        else r = open > 0 ? new Result(ChunkPos.getX(key), ChunkPos.getZ(key), open, clusters, buds, minY, maxY) : null;

        Result old = r == null ? results.remove(key) : results.put(key, r);
        if (old != null || r != null) areasDirty = true;
        if (r != null && flagged(r) && announced.add(key) && announce.get()) {
            String g = r.open() < 0 ? "fully grown (palette)" : Math.round(r.growth() * 100) + "% grown, " + r.open() + " faces";
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
        }
        if (area.get()) {
            double y = Math.floor(me.y) - 0.98;
            for (Area a : areas) {
                AABB box = new AABB(a.x0() * 16, y, a.z0() * 16, (a.x1() + 1) * 16, y + 0.05, (a.z1() + 1) * 16);
                Render3D.box(box, ColorUtil.withAlpha(c, 24), ColorUtil.withAlpha(c, 200), true);
            }
        }
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
            String text = r.open() < 0 ? "grown amethyst" : Math.round(r.growth() * 100) + "% grown";
            ctx.text(mc.font, text, (int) s[0] - mc.font.width(text) / 2, (int) s[1], color.color(), true);
        }
    }
}
