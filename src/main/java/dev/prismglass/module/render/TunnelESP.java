package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * Highlights 1x2 tunnels (player-dug passages: two air blocks high, walls on both sides, floor and ceiling) in the
 * loaded chunks around you. Straight runs of at least MinLength blocks along X or Z are merged into one box.
 * Scans ChunksPerTick chunks a tick, closest first, caches each chunk's tunnels and rescans it every Rescan
 * seconds, so it never stalls a frame.
 */
public class TunnelESP extends Module {
    public final NumberSetting range = num("Range", 6, 2, 16, 1, "Radius in chunks.");
    public final NumberSetting height = num("Height", 40, 8, 192, 4, "Blocks above and below you to scan.");
    public final NumberSetting minLength = num("MinLength", 4, 2, 16, 1, "Shortest straight run that counts as a tunnel.");
    public final NumberSetting perTick = num("ChunksPerTick", 2, 1, 8, 1, "Chunks scanned per tick.");
    public final NumberSetting rescan = num("Rescan", 10, 2, 60, 1, "Seconds before a chunk is scanned again.");
    public final ColorSetting color = color("Color", 0x40FFB84D, "Tunnel colour.");
    public final BoolSetting throughWalls = bool("ThroughWalls", true, "Draw through blocks.");

    private final Long2ObjectOpenHashMap<List<AABB>> tunnels = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap scannedAt = new Long2LongOpenHashMap();
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
    private long tick;

    public TunnelESP() { super("TunnelESP", "Highlights 1x2 tunnels in loaded chunks.", Category.RENDER); }

    @Override public void onDisable() { tunnels.clear(); scannedAt.clear(); }

    @Override public void onWorldJoin() { tunnels.clear(); scannedAt.clear(); }

    @Override
    public void onTick() {
        tick++;
        int r = range.getInt();
        ChunkPos me = mc.player.chunkPosition();
        // forget chunks that left the range
        tunnels.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - me.x()), Math.abs(ChunkPos.getZ(k) - me.z())) > r + 1);
        scannedAt.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - me.x()), Math.abs(ChunkPos.getZ(k) - me.z())) > r + 1);
        long stale = rescan.getInt() * 20L;
        int budget = perTick.getInt();
        // rings outward: the closest stale chunks first
        for (int ring = 0; ring <= r && budget > 0; ring++) {
            for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = ChunkPos.pack(me.x() + dx, me.z() + dz);
                    if (scannedAt.containsKey(key) && tick - scannedAt.get(key) < stale) continue;
                    LevelChunk chunk = mc.level.getChunkSource().getChunk(me.x() + dx, me.z() + dz, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    tunnels.put(key, scan(chunk));
                    scannedAt.put(key, tick);
                    budget--;
                }
            }
        }
    }

    private List<AABB> scan(LevelChunk chunk) {
        List<AABB> out = new ArrayList<>();
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        int py = mc.player.getBlockY(), h = height.getInt();
        int minY = Math.max(mc.level.getMinY() + 1, py - h), maxY = Math.min(mc.level.getMaxY() - 2, py + h);
        int min = minLength.getInt();
        for (int y = minY; y <= maxY; y++) {
            // runs along X (walls north and south), one row of z at a time
            for (int z = 0; z < 16; z++) {
                int run = 0;
                for (int x = 0; x <= 16; x++) {
                    boolean cell = x < 16 && cell(chunk, bx + x, y, bz + z) && wall(chunk, bx + x, y, bz + z - 1) && wall(chunk, bx + x, y, bz + z + 1);
                    if (cell) { run++; continue; }
                    if (run >= min) out.add(new AABB(bx + x - run, y, bz + z, bx + x, y + 2, bz + z + 1));
                    run = 0;
                }
            }
            // runs along Z (walls west and east)
            for (int x = 0; x < 16; x++) {
                int run = 0;
                for (int z = 0; z <= 16; z++) {
                    boolean cell = z < 16 && cell(chunk, bx + x, y, bz + z) && wall(chunk, bx + x - 1, y, bz + z) && wall(chunk, bx + x + 1, y, bz + z);
                    if (cell) { run++; continue; }
                    if (run >= min) out.add(new AABB(bx + x, y, bz + z - run, bx + x + 1, y + 2, bz + z));
                    run = 0;
                }
            }
        }
        return out;
    }

    /** Two air blocks with a floor under and a ceiling over. */
    private boolean cell(LevelChunk c, int x, int y, int z) {
        return state(c, x, y, z).isAir() && state(c, x, y + 1, z).isAir() && dev.prismglass.util.BlockUtil.blocksMotion(state(c, x, y - 1, z)) && dev.prismglass.util.BlockUtil.blocksMotion(state(c, x, y + 2, z));
    }

    /** Both blocks beside the passage are solid (neighbouring chunks are read through the level). */
    private boolean wall(LevelChunk c, int x, int y, int z) {
        return dev.prismglass.util.BlockUtil.blocksMotion(state(c, x, y, z)) && dev.prismglass.util.BlockUtil.blocksMotion(state(c, x, y + 1, z));
    }

    private net.minecraft.world.level.block.state.BlockState state(LevelChunk c, int x, int y, int z) {
        m.set(x, y, z);
        if ((x >> 4) == c.getPos().x() && (z >> 4) == c.getPos().z()) return c.getBlockState(m);
        return mc.level.getBlockState(m);
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        int col = color.color();
        int line = ColorUtil.withAlpha(col, 0xC0);
        for (List<AABB> list : tunnels.values()) for (AABB box : list) Render3D.box(box, col, line, throughWalls.get());
    }

    @Override
    public String getInfo() {
        int n = 0;
        for (List<AABB> list : tunnels.values()) n += list.size();
        return n == 0 ? null : String.valueOf(n);
    }
}
