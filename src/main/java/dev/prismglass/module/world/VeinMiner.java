package dev.prismglass.module.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.*;

/**
 * Break one block of a vein (ore, log...) and it mines the rest of the connected blocks of the same kind, one at a
 * time with vanilla timing and the rotation on each block (like Nuker's survival mode, so Grim-safe). Blocks out of
 * reach wait in the queue until you walk closer; it stops when the queue is empty or you turn it off.
 */
public class VeinMiner extends Module {
    public final ModeSetting filter = mode("Filter", "Ores", "Which blocks start a vein.", "Ores", "Logs", "OresLogs", "Any");
    public final NumberSetting limit = num("Limit", 32, 2, 256, 1, "Most blocks in one vein.");
    public final BoolSetting diagonal = bool("Diagonal", true, "Count blocks touching only at an edge or corner.");
    public final NumberSetting range = num("Range", 4.5, 2, 6, 0.5, "Mine queued blocks this close.");
    public final BoolSetting deepslate = bool("SameOre", true, "Stone and deepslate versions of an ore count as one vein.");
    public final ColorSetting color = color("Color", 0x7055D6FF, "Outline of the queued blocks.");

    private final List<BlockPos> queue = new ArrayList<>();
    private BlockPos current;
    private Direction face = Direction.UP;

    public VeinMiner() { super("VeinMiner", "Break one block of a vein and it mines the rest.", Category.WORLD); }

    @Override public void onDisable() { if (current != null) mc.gameMode.stopDestroyBlock(); current = null; queue.clear(); }

    /** InteractionManagerMixin: the client finished breaking a block (state = what it was). */
    public void onBroken(BlockPos pos, BlockState state) {
        if (!queue.isEmpty() || current != null || !starts(state)) return;
        Block kind = state.getBlock();
        Set<BlockPos> seen = new HashSet<>(List.of(pos));
        ArrayDeque<BlockPos> open = new ArrayDeque<>(List.of(pos));
        while (!open.isEmpty() && queue.size() < limit.getInt() - 1) {
            BlockPos at = open.poll();
            for (BlockPos n : neighbours(at)) {
                if (!seen.add(n) || !same(kind, BlockUtil.state(n))) continue;
                queue.add(n);
                open.add(n);
                if (queue.size() >= limit.getInt() - 1) break;
            }
        }
    }

    private List<BlockPos> neighbours(BlockPos p) {
        List<BlockPos> out = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            if (n == 0 || (n > 1 && !diagonal.get())) continue;
            out.add(p.offset(dx, dy, dz));
        }
        return out;
    }

    private boolean starts(BlockState s) {
        boolean ore = isOre(s.getBlock()), log = s.is(BlockTags.LOGS);
        return switch (filter.get()) {
            case "Ores" -> ore;
            case "Logs" -> log;
            case "OresLogs" -> ore || log;
            default -> !s.isAir();
        };
    }

    private static boolean isOre(Block b) {
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    /** "deepslate_iron_ore" and "iron_ore" are the same vein when SameOre is on. */
    private boolean same(Block kind, BlockState s) {
        if (s.getBlock() == kind) return true;
        if (!deepslate.get() || !isOre(kind) || !isOre(s.getBlock())) return false;
        return strip(kind).equals(strip(s.getBlock()));
    }

    private static String strip(Block b) { return BuiltInRegistries.BLOCK.getKey(b).getPath().replace("deepslate_", "").replace("nether_", ""); }

    @Override
    public void onTick() {
        double r = Prism.anticheat().placeRange(range.get());
        if (current != null && (BlockUtil.isReplaceable(current) || far(current, r))) { if (!BlockUtil.isReplaceable(current)) mc.gameMode.stopDestroyBlock(); current = null; }
        queue.removeIf(BlockUtil::isReplaceable);
        if (current == null) {
            double best = Double.MAX_VALUE;
            for (BlockPos p : queue) {
                if (far(p, r) || BlockUtil.breakFace(p) == null) continue;
                double d = mc.player.getEyePosition().distanceToSqr(p.getCenter());
                if (d < best) { best = d; current = p; }
            }
            if (current == null) return;
            queue.remove(current);
            face = BlockUtil.breakFace(current);
        }
        if (!BlockUtil.readyToDig(current, 30)) return;
        mc.gameMode.continueDestroyBlock(current, face);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean far(BlockPos p, double r) { return RotationUtil.distanceToBox(mc.player.getEyePosition(), new AABB(p)) > r; }

    public boolean busy() { return current != null || !queue.isEmpty(); }
    public int queued() { return queue.size() + (current == null ? 0 : 1); }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        int c = color.color();
        for (BlockPos p : queue) Render3D.box(new AABB(p), 0, ColorUtil.withAlpha(c, 120), false);
        if (current != null) Render3D.box(new AABB(current), ColorUtil.withAlpha(c, 50), ColorUtil.withAlpha(c, 255), false);
    }

    @Override public String getInfo() { return busy() ? String.valueOf(queued()) : null; }
}
