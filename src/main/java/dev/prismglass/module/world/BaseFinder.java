package dev.prismglass.module.world;

import net.minecraft.resources.ResourceKey;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base hunting. Every loaded chunk gets a suspicion score from:
 * <ul>
 *   <li>block entities (storage, beds, beacons, signs...) weighted by how rarely they generate naturally</li>
 *   <li>blocks that don't belong in the dimension (obsidian, smooth stone, glass, stripped wood, concrete,
 *       nether blocks in the overworld...), each type capped so one natural feature (an obsidian lava
 *       pool, a village) can't flag a chunk on its own</li>
 * </ul>
 * New chunks are detected with the fluid-flow method: freshly generated terrain sends flowing water/lava
 * updates right after it loads, settled (already visited) terrain doesn't. A base can't be in a chunk that
 * was just generated, so new chunks are ignored by default - that alone kills most structure false positives.
 *
 * <p>Scanning runs from a queue with a per-tick time budget, and each 16x16x16 section is skipped unless its
 * palette contains a block we care about, so this stays cheap while flying with a large view distance.
 */
public class BaseFinder extends Module {
    public final NumberSetting threshold = num("Threshold", 30, 5, 200, 1, "Score needed to flag a chunk.");
    public final BoolSetting ignoreNew = bool("IgnoreNewChunks", true, "Never flag freshly generated chunks.");
    public final BoolSetting countSpawners = bool("Spawners", false, "Count spawners (mostly natural dungeons).");
    public final BoolSetting showNew = bool("ShowNewChunks", true, "Mark freshly generated chunks.");
    public final BoolSetting boxes = bool("Boxes", true, "Draw a box around flagged chunks.");
    public final BoolSetting tracers = bool("Tracers", false, "Lines to flagged chunks.");
    public final BoolSetting minimap = bool("Minimap", true, "Glass radar of chunks around you.");
    public final NumberSetting mapRadius = num("MapRadius", 10, 4, 24, 1, "Minimap radius in chunks.");
    public final NumberSetting mapCell = num("MapCell", 4, 2, 8, 1, "Pixels per chunk on the minimap.");
    public final ModeSetting mapCorner = mode("MapCorner", "TopRight", "Minimap position.", "TopRight", "TopLeft", "BottomRight", "BottomLeft");
    public final BoolSetting notifyFinds = bool("Announce", true, "Chat message + log file entry for each find.");
    public final NumberSetting budget = num("BudgetMs", 2, 0.5, 10, 0.5, "Max scan time per tick (ms).");

    public record ChunkResult(int x, int z, float score, int storage, int spawners, int minY, int maxY, String top) {
        boolean flagged(float threshold) { return score >= threshold; }
    }

    private static BaseFinder instance;

    private final Map<Long, ChunkResult> results = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    private final Set<Long> newChunks = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> loadTimes = new ConcurrentHashMap<>();
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private final LinkedHashSet<Long> queue = new LinkedHashSet<>();
    private Map<Block, Float> weights = Map.of();
    private Map<Block, Float> caps = Map.of();
    private net.minecraft.resources.ResourceKey<Level> dimension;
    private int flaggedCount;

    public BaseFinder() {
        super("BaseFinder", "Finds bases, stashes and player-touched chunks.", Category.WORLD);
        instance = this;
    }

    /** Pre-filter for other ESPs: true when BaseFinder is off, or the position is in a flagged chunk. */
    public static boolean allows(BlockPos pos) {
        if (instance == null || !instance.isEnabled()) return true;
        ChunkResult r = instance.results.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        return r != null && r.flagged(instance.threshold.getFloat());
    }

    @Override
    public void onEnable() {
        reset();
        enqueueLoaded();
    }

    @Override public void onWorldJoin() { reset(); }

    private void reset() {
        results.clear();
        announced.clear();
        newChunks.clear();
        loadTimes.clear();
        dirty.clear();
        queue.clear();
        flaggedCount = 0;
        dimension = null;
    }

    private void enqueueLoaded() {
        if (mc.player == null) return;
        int r = mc.options.renderDistance().get() + 1;
        ChunkPos c = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) queue.add(ChunkPos.pack(c.x() + x, c.z() + z));
    }

    // ---- packets (netty thread: only touch the concurrent collections) ----------------------------

    @Override
    public void onPacketReceive(PacketEvent event) {
        long now = System.currentTimeMillis();
        if (event.packet instanceof ClientboundLevelChunkWithLightPacket p) {
            long key = ChunkPos.pack(p.getX(), p.getZ());
            loadTimes.put(key, now);
            dirty.add(key);
        } else if (event.packet instanceof ClientboundBlockUpdatePacket p) {
            onBlockUpdate(p.getPos(), p.getBlockState(), now);
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            p.runUpdates((pos, state) -> onBlockUpdate(pos, state, now));
        }
    }

    private void onBlockUpdate(BlockPos pos, BlockState state, long now) {
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        dirty.add(key);
        // flowing fluid shortly after the chunk arrived = terrain that was just generated
        var fluid = state.getFluidState();
        if (!fluid.isEmpty() && !fluid.isSource()) {
            Long loaded = loadTimes.get(key);
            if (loaded != null && now - loaded < 3000) newChunks.add(key);
        }
    }

    // ---- scanning --------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (dimension != mc.level.dimension()) {
            reset();
            dimension = mc.level.dimension();
            buildWeights();
            enqueueLoaded();
        }
        long now = System.currentTimeMillis();
        for (Iterator<Long> it = dirty.iterator(); it.hasNext(); ) {
            long key = it.next();
            // give a fresh chunk time to send its fluid updates, so new chunks are known before scoring
            Long loaded = loadTimes.get(key);
            if (loaded != null && now - loaded < 1500) continue;
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
            else if (loadTimes.containsKey(key) && now - loadTimes.get(key) < 10000) dirty.add(key); // data not applied yet
        }
    }

    private void scan(long key, LevelChunk chunk) {
        float score = 0;
        int storage = 0, spawners = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        String top = null;
        float topScore = 0;

        // block entities
        Map<String, Float> beScore = new HashMap<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            float w = blockEntityWeight(be);
            if (be instanceof SpawnerBlockEntity) { spawners++; if (!countSpawners.get()) continue; }
            if (w <= 0) continue;
            if (isStorage(be)) storage++;
            score += w;
            String name = label(be);
            beScore.merge(name, w, Float::sum);
            int y = be.getBlockPos().getY();
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        for (var e : beScore.entrySet()) if (e.getValue() > topScore) { topScore = e.getValue(); top = e.getKey(); }

        // unnatural blocks, palette-filtered per section
        Map<Block, Float> blockScore = new HashMap<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> weights.containsKey(st.getBlock()))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Block b = s.getBlockState(x, y, z).getBlock();
                Float w = weights.get(b);
                if (w == null) continue;
                blockScore.merge(b, w, Float::sum);
                minY = Math.min(minY, baseY + y);
                maxY = Math.max(maxY, baseY + y);
            }
        }
        for (var e : blockScore.entrySet()) {
            float capped = Math.min(e.getValue(), caps.getOrDefault(e.getKey(), 40f));
            score += capped;
            if (capped > topScore) { topScore = capped; top = e.getKey().getName().getString(); }
        }

        boolean isNew = newChunks.contains(key);
        if (isNew && ignoreNew.get()) score = 0;
        if (score <= 0 && spawners == 0) { results.remove(key); return; }

        ChunkResult r = new ChunkResult(chunk.getPos().x(), chunk.getPos().z(), score, storage, spawners,
            minY == Integer.MAX_VALUE ? mc.player.getBlockY() : minY, maxY == Integer.MIN_VALUE ? mc.player.getBlockY() : maxY, top);
        results.put(key, r);
        if (r.flagged(threshold.getFloat()) && announced.add(key)) {
            flaggedCount++;
            if (notifyFinds.get()) announce(r);
        }
    }

    private void announce(ChunkResult r) {
        String line = String.format("Base? chunk %d,%d (block %d %d %d) score %.0f, %d storage, top: %s [%s]",
            r.x(), r.z(), r.x() * 16 + 8, (r.minY() + r.maxY()) / 2, r.z() * 16 + 8, r.score(), r.storage(),
            r.top() == null ? "?" : r.top(), mc.level.dimension().identifier().getPath());
        ChatUtil.good(line);
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("bases.txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, java.time.LocalDateTime.now().withNano(0) + " " + line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    // ---- weights -------------------------------------------------------------------------------------

    private static boolean isStorage(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity
            || be instanceof EnderChestBlockEntity || be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity;
    }

    private static float blockEntityWeight(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity) return 12;
        if (be instanceof BeaconBlockEntity) return 15;
        if (be instanceof EnderChestBlockEntity) return 8;
        if (be instanceof TrappedChestBlockEntity) return 6;
        if (be instanceof ChestBlockEntity) return 3;        // dungeons/villages have a few, bases have many
        if (be instanceof BarrelBlockEntity) return 3;
        if (be instanceof HopperBlockEntity) return 5;
        if (be instanceof AbstractFurnaceBlockEntity) return 3;
        if (be instanceof DispenserBlockEntity) return 3;    // includes droppers
        if (be instanceof BedBlockEntity) return 6;
        if (be instanceof EnchantingTableBlockEntity) return 6;
        if (be instanceof BrewingStandBlockEntity) return 5;
        if (be instanceof SignBlockEntity) return 3;
        if (be instanceof BannerBlockEntity) return 3;
        if (be instanceof SpawnerBlockEntity) return 2;
        return 0;
    }

    private static String label(BlockEntity be) {
        return be.getBlockState().getBlock().getName().getString();
    }

    /** Per-block weight and cap for the current dimension. */
    private void buildWeights() {
        Map<Block, Float> w = new HashMap<>();
        Map<Block, Float> cap = new HashMap<>();
        boolean overworld = dimension == Level.OVERWORLD, nether = dimension == Level.NETHER, end = dimension == Level.END;

        // obsidian forms naturally where lava meets water, so it is weak and capped
        put(w, cap, 0.4f, 10, Blocks.OBSIDIAN);
        put(w, cap, 1.5f, 30, Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB);
        put(w, cap, 1.2f, 40, Blocks.GLASS, Blocks.GLASS_PANE, Blocks.TINTED_GLASS);
        put(w, cap, 2f, 40, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_JUNGLE_LOG,
            Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STRIPPED_MANGROVE_LOG, Blocks.STRIPPED_CHERRY_LOG,
            Blocks.STRIPPED_OAK_WOOD, Blocks.STRIPPED_SPRUCE_WOOD, Blocks.STRIPPED_BIRCH_WOOD, Blocks.STRIPPED_DARK_OAK_WOOD,
            Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_WARPED_STEM);
        // planks: villages/mineshafts have them, so weak
        put(w, cap, 0.15f, 15, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS,
            Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS);
        put(w, cap, 2.5f, 50, Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE,
            Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE, Blocks.PINK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE,
            Blocks.RED_CONCRETE, Blocks.BLACK_CONCRETE);
        put(w, cap, 2f, 30, Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR, Blocks.BRICKS,
            Blocks.SEA_LANTERN, Blocks.IRON_BARS, Blocks.SCAFFOLDING, Blocks.HONEY_BLOCK, Blocks.SLIME_BLOCK);
        put(w, cap, 3f, 40, Blocks.CRAFTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.GRINDSTONE,
            Blocks.STONECUTTER, Blocks.SMITHING_TABLE, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE, Blocks.ENDER_CHEST,
            Blocks.REPEATER, Blocks.COMPARATOR, Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.REDSTONE_LAMP,
            Blocks.NOTE_BLOCK, Blocks.TARGET, Blocks.LECTERN, Blocks.COMPOSTER);
        put(w, cap, 6f, 60, Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.NETHERITE_BLOCK,
            Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.RESPAWN_ANCHOR, Blocks.CONDUIT, Blocks.LODESTONE);
        put(w, cap, 1f, 20, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH);
        put(w, cap, 1f, 20, Blocks.CRYING_OBSIDIAN, Blocks.WHITE_WOOL, Blocks.WHITE_CARPET, Blocks.BLACK_WOOL, Blocks.RED_WOOL);

        if (overworld || end) {
            // nether blocks out of place (ruined portals add a little netherrack, hence the cap)
            put(w, cap, 0.8f, 15, Blocks.NETHERRACK, Blocks.MAGMA_BLOCK);
            put(w, cap, 2.5f, 40, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT,
                Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS);
        }
        if (overworld || nether) {
            put(w, cap, 3f, 40, Blocks.END_STONE, Blocks.END_STONE_BRICKS, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.END_ROD);
        }
        if (nether) {
            // in the nether, overworld materials are the giveaway
            put(w, cap, 1.5f, 30, Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.OAK_LOG,
                Blocks.SPRUCE_LOG);
            w.put(Blocks.OBSIDIAN, 1.2f); // naturally rare there (only portals)
            cap.put(Blocks.OBSIDIAN, 40f);
        }
        weights = w;
        caps = cap;
    }

    private static void put(Map<Block, Float> w, Map<Block, Float> cap, float weight, float capValue, Block... blocks) {
        for (Block b : blocks) { w.put(b, weight); cap.put(b, capValue); }
    }

    // ---- rendering -----------------------------------------------------------------------------------

    private int severityColor(ChunkResult r) {
        float t = Mth.clamp((r.score() / threshold.getFloat() - 1f) / 2f, 0f, 1f);
        return ColorUtil.lerp(0xFFFFD24D, 0xFFFF3B5C, t);
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        float th = threshold.getFloat();
        int view = (mc.options.renderDistance().get() + 2) * 16;
        Vec3 me = mc.player.position();
        for (ChunkResult r : results.values()) {
            if (!r.flagged(th)) continue;
            double cx = r.x() * 16 + 8, cz = r.z() * 16 + 8;
            if (Math.abs(cx - me.x) > view || Math.abs(cz - me.z) > view) continue;
            int c = severityColor(r);
            if (boxes.get()) {
                AABB b = new AABB(r.x() * 16, r.minY(), r.z() * 16, r.x() * 16 + 16, r.maxY() + 1, r.z() * 16 + 16);
                Render3D.box(b, ColorUtil.withAlpha(c, 28), ColorUtil.withAlpha(c, 220), true);
            }
            if (tracers.get()) Render3D.tracer(new Vec3(cx, (r.minY() + r.maxY()) / 2.0, cz), ColorUtil.withAlpha(c, 170));
        }
        if (showNew.get()) {
            double y = Math.floor(me.y) - 0.98;
            for (long key : newChunks) {
                int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
                if (Math.abs(x * 16 + 8 - me.x) > view || Math.abs(z * 16 + 8 - me.z) > view) continue;
                Render3D.plate(new AABB(x * 16, y, z * 16, x * 16 + 16, y + 0.02, z * 16 + 16), 0x2650FF8C, 0, false);
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (!minimap.get() || mc.options.hideGui) return;
        int radius = mapRadius.getInt(), cell = mapCell.getInt();
        int size = (radius * 2 + 1) * cell;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        int pad = 6;
        int x0 = mapCorner.get().endsWith("Left") ? 6 : sw - size - pad * 2 - 6;
        int y0 = mapCorner.get().startsWith("Top") ? 26 : sh - size - pad * 2 - 40;

        Glass.panel(ctx, x0, y0, size + pad * 2, size + pad * 2, Prism.modules().get(ClickGui.class).panelStyle());
        ChunkPos c = mc.player.chunkPosition();
        float th = threshold.getFloat();
        int ox = x0 + pad, oy = y0 + pad;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long key = ChunkPos.pack(c.x() + dx, c.z() + dz);
                int color;
                ChunkResult r = results.get(key);
                if (r != null && r.flagged(th)) color = severityColor(r);
                else if (newChunks.contains(key)) color = 0x6650FF8C;
                else if (loadTimes.containsKey(key)) color = r != null && r.score() > th * 0.4f ? 0x55FFD24D : 0x22FFFFFF;
                else continue;
                int px = ox + (dx + radius) * cell, py = oy + (dz + radius) * cell;
                ctx.fill(px, py, px + cell - 1, py + cell - 1, color);
            }
        }
        // player marker + heading
        int mx = ox + radius * cell + cell / 2, my = oy + radius * cell + cell / 2;
        ctx.fill(mx - 1, my - 1, mx + 2, my + 2, 0xFFFFFFFF);
        double yaw = Math.toRadians(mc.player.getYRot());
        for (int i = 2; i <= cell * 2 + 2; i++) {
            int hx = mx + (int) Math.round(-Math.sin(yaw) * i), hy = my + (int) Math.round(Math.cos(yaw) * i);
            ctx.fill(hx, hy, hx + 1, hy + 1, 0xCCFFFFFF);
        }
        String label = flaggedCount + " found";
        ctx.text(mc.font, label, x0 + pad, y0 + size + pad * 2 + 2, 0xFFFFFFFF, true);
    }

    @Override public String getInfo() { return String.valueOf(flaggedCount); }

    public Collection<ChunkResult> results() { return results.values(); }
}
