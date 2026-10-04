package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import dev.prismglass.xray.OreSimulator;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Ore finder with three modes:
 * <ul>
 *   <li><b>Normal</b>: highlights the ores the server sent you. Paper anti-xray hides most of them.</li>
 *   <li><b>Exposed</b>: only ores touching air/water. Paper anti-xray (engine 1, 2 and 3) only disguises blocks
 *       that are NOT exposed, so every exposed ore is guaranteed real - this filters out all fake ores.</li>
 *   <li><b>Seed</b>: re-runs vanilla ore generation for the world seed ({@code .seed <seed>}) and shows where ores
 *       generated, ignoring what the server sends. Anti-xray can't affect it. Ores you can already see as mined
 *       (air) are hidden; ones the server confirms are drawn solid.</li>
 * </ul>
 */
public class Xray extends Module {
    public final ModeSetting mode = mode("Mode", "Exposed", "Normal = what the server sends. Exposed = only ores touching air (always real). Seed = predicted from the world seed.",
        "Exposed", "Normal", "Seed");
    public final ListSetting blocks = list("Blocks",
        "diamond_ore,deepslate_diamond_ore,ancient_debris,emerald_ore,deepslate_emerald_ore,gold_ore,deepslate_gold_ore,nether_gold_ore,iron_ore,deepslate_iron_ore,redstone_ore,deepslate_redstone_ore,lapis_ore,deepslate_lapis_ore",
        "Block ids to show.", ListSetting.Kind.BLOCK);
    public final TextSetting seed = text("Seed", "", "World seed for Seed mode (also: .seed <seed>).");
    public final NumberSetting range = num("ChunkRange", 5, 1, 12, 1, "Chunks around you.");
    public final NumberSetting fill = num("FillAlpha", 45, 0, 255, 1, "Box fill opacity.");
    public final BoolSetting tracers = bool("Tracers", false, "Lines to found ores.");

    private Set<Block> targets = Set.of();
    private String lastList = "";
    // Normal/Exposed: results per chunk, refreshed a few chunks per tick (nearest first) under a time budget,
    // so there is never a whole-area rescan in one tick
    private static final long TICK_BUDGET_NS = 2_000_000;
    private static final int MAX_PER_CHUNK = 512;
    private final Map<Long, List<BlockPos>> chunkHits = new HashMap<>();
    private final ArrayDeque<Long> scanQueue = new ArrayDeque<>();
    private String scanMode = "";
    private int passCooldown;
    private final Map<Long, List<OreSimulator.Ore>> simulated = new HashMap<>();
    private OreSimulator simulator;
    private String simSeed = "";

    public Xray() { super("Xray", "Finds ores (with an anti-xray bypass).", Category.RENDER); }

    @Override public void onEnable() { resetScan(); simulated.clear(); }

    private void resetScan() { chunkHits.clear(); scanQueue.clear(); }
    @Override public void onWorldJoin() { resetScan(); simulated.clear(); simulator = null; simSeed = ""; }

    @Override
    public void onTick() {
        if (!blocks.get().equals(lastList)) {
            lastList = blocks.get();
            Set<Block> s = new HashSet<>();
            for (String id : lastList.split(",")) {
                Identifier i = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
                if (i != null && BuiltInRegistries.BLOCK.containsKey(i)) s.add(BuiltInRegistries.BLOCK.getValue(i));
            }
            targets = s;
            simulated.clear();
            resetScan();
        }
        if (mode.is("Seed")) { tickSeed(); return; }
        if (!mode.get().equals(scanMode)) { scanMode = mode.get(); resetScan(); }
        scanServerData();
    }

    // ---- Normal / Exposed -------------------------------------------------------------------------

    private void scanServerData() {
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z(), r = range.getInt();
        if (scanQueue.isEmpty()) {
            if (passCooldown-- > 0) return; // short rest between full passes keeps the average cost low
            passCooldown = 10;
            // start a new pass, nearest rings first so what's around you shows up immediately
            for (int d = 0; d <= r; d++) {
                for (int x = cx - d; x <= cx + d; x++) for (int z = cz - d; z <= cz + d; z++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == d) scanQueue.add(ChunkPos.pack(x, z));
                }
            }
            chunkHits.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - cx), Math.abs(ChunkPos.getZ(k) - cz)) > r);
        }
        boolean exposedOnly = mode.is("Exposed");
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        while (!scanQueue.isEmpty() && System.nanoTime() < deadline) {
            long key = scanQueue.poll();
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            List<BlockPos> hits = chunk == null ? List.of() : scanChunk(chunk, exposedOnly);
            if (hits.isEmpty()) chunkHits.remove(key);
            else chunkHits.put(key, hits);
        }
    }

    private List<BlockPos> scanChunk(LevelChunk chunk, boolean exposedOnly) {
        List<BlockPos> out = new ArrayList<>();
        int ox = chunk.getPos().x() << 4, oz = chunk.getPos().z() << 4;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> targets.contains(st.getBlock()))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                if (!targets.contains(s.getBlockState(bx, by, bz).getBlock())) continue;
                BlockPos pos = new BlockPos(ox + bx, baseY + by, oz + bz);
                if (exposedOnly && !isExposed(pos)) continue;
                out.add(pos);
                if (out.size() >= MAX_PER_CHUNK) return out;
            }
        }
        return out;
    }

    /** Paper anti-xray leaves blocks next to transparent blocks untouched, so these are real. */
    private boolean isExposed(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState n = mc.level.getBlockState(pos.relative(d));
            if (n.isAir() || !n.getFluidState().isEmpty() || !n.isSolidRender()) return true;
        }
        return false;
    }

    // ---- Seed ---------------------------------------------------------------------------------------

    private void tickSeed() {
        String s = seed.get().trim();
        if (s.isEmpty()) return;
        if (simulator == null || !s.equals(simSeed) || !simulator.isFor(mc.level)) {
            simSeed = s;
            simulated.clear();
            simulator = OreSimulator.create(mc.level, parseSeed(s));
        }
        if (simulator == null) return;
        // a few chunks per tick under a time budget so flying around stays smooth
        int budget = 6;
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z(), r = range.getInt();
        for (int d = 0; d <= r && budget > 0; d++) {
            for (int x = cx - d; x <= cx + d && budget > 0; x++) for (int z = cz - d; z <= cz + d && budget > 0; z++) {
                if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != d) continue;
                long key = ChunkPos.pack(x, z);
                if (simulated.containsKey(key) || mc.level.getChunkSource().getChunkNow(x, z) == null) continue;
                if (System.nanoTime() > deadline) { budget = 0; break; }
                simulated.put(key, simulator.simulate(new ChunkPos(x, z), targets));
                budget--;
            }
        }
        simulated.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - cx), Math.abs(ChunkPos.getZ(k) - cz)) > r + 2);
    }

    public static long parseSeed(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return s.hashCode(); }
    }

    // ---- render -------------------------------------------------------------------------------------

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        if (mode.is("Seed")) {
            for (List<OreSimulator.Ore> list : simulated.values()) {
                for (OreSimulator.Ore ore : list) {
                    BlockState now = mc.level.getBlockState(ore.pos());
                    if (now.isAir() || !now.getFluidState().isEmpty()) continue; // mined / cave
                    boolean confirmed = now.getBlock() == ore.block();
                    int c = colorOf(ore.block());
                    Render3D.box(new AABB(ore.pos()), ColorUtil.withAlpha(c, confirmed ? fill.getInt() * 2 : fill.getInt()),
                        ColorUtil.withAlpha(c, confirmed ? 255 : 170), true);
                    if (tracers.get()) Render3D.tracer(ore.pos().getCenter(), ColorUtil.withAlpha(c, 120));
                }
            }
            return;
        }
        for (List<BlockPos> list : chunkHits.values()) {
            for (BlockPos p : list) {
                Block b = mc.level.getBlockState(p).getBlock();
                if (!targets.contains(b)) continue; // mined since the last scan
                int c = colorOf(b);
                Render3D.box(new AABB(p), ColorUtil.withAlpha(c, fill.getInt()), c, true);
                if (tracers.get()) Render3D.tracer(p.getCenter(), ColorUtil.withAlpha(c, 120));
            }
        }
    }

    private static int colorOf(Block b) {
        if (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE) return 0xFF4DF0FF;
        if (b == Blocks.ANCIENT_DEBRIS) return 0xFFB06A4A;
        if (b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE) return 0xFF3CFF7A;
        if (b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE || b == Blocks.NETHER_GOLD_ORE) return 0xFFFFD23C;
        if (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE) return 0xFFE8B89A;
        if (b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE) return 0xFFFF3B3B;
        if (b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE) return 0xFF3B6BFF;
        if (b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE) return 0xFFFF9A5A;
        if (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE) return 0xFF505050;
        return 0xFFFFFFFF;
    }

    @Override
    public String getInfo() {
        if (mode.is("Seed")) {
            int n = 0;
            for (List<OreSimulator.Ore> l : simulated.values()) n += l.size();
            return "Seed " + n;
        }
        int n = 0;
        for (List<BlockPos> l : chunkHits.values()) n += l.size();
        return mode.get() + " " + n;
    }
}
