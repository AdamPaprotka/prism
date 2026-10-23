package dev.prismglass.module.combat;

import dev.prismglass.mixin.LevelRendererAccessor;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.Blocks;

/**
 * Defends your surround against being "citied". The server tells you when someone is mining a block near you
 * (block destruction progress, the same data BreakIndicators shows). While a surround block is being mined:
 * the spot beyond it (where they'd put the crystal) gets a block, so that crystal spot is gone. The moment the
 * surround block breaks, it's put back. Placing happens one block per tick, closest threat first.
 */
public class AntiCity extends Module {
    public final BoolSetting extend = bool("Extend", true, "Block the crystal spot beyond a surround block that's being mined.");
    public final BoolSetting replace = bool("Replace", true, "Put a surround block back the moment it breaks.");
    public final BoolSetting onlyInHole = bool("OnlyInHole", true, "Only while you're surrounded.");
    public final NumberSetting watch = num("Watch", 40, 5, 200, 5, "Ticks to keep guarding a block after its mining stopped.");

    /** Surround blocks someone is mining, and how many ticks to keep watching them. */
    private final java.util.Map<BlockPos, Integer> threatened = new java.util.HashMap<>();

    public AntiCity() { super("AntiCity", "Protects your surround while it's being mined (extends + replaces).", Category.COMBAT); }

    @Override public void onDisable() { threatened.clear(); }

    @Override
    public void onTick() {
        List<BlockPos> surround = Surround.positions();
        Set<BlockPos> ring = new LinkedHashSet<>(surround);
        var map = ((LevelRendererAccessor) mc.levelRenderer).prism$destroyingBlocks();
        for (var e : map.int2ObjectEntrySet()) {
            if (e.getIntKey() == mc.player.getId()) continue; // our own mining
            BlockDestructionProgress p = e.getValue();
            if (ring.contains(p.getPos())) threatened.put(p.getPos().immutable(), watch.getInt());
        }
        threatened.replaceAll((k, v) -> v - 1);
        threatened.values().removeIf(v -> v <= 0);
        if (threatened.isEmpty()) return;
        if (onlyInHole.get() && threatened.keySet().stream().noneMatch(ring::contains)) return;

        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ENDER_CHEST);
        if (slot == -1) return;
        BlockPos feet = BlockUtil.playerPos();
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos b : threatened.keySet()) {
            if (replace.get() && BlockUtil.isReplaceable(b)) todo.add(b); // broken: put it back first
        }
        if (extend.get()) {
            for (BlockPos b : threatened.keySet()) {
                Direction d = Direction.getApproximateNearest(b.getX() - feet.getX(), 0, b.getZ() - feet.getZ());
                BlockPos beyond = b.relative(d);
                if (BlockUtil.isReplaceable(beyond)) todo.add(beyond);
            }
        }
        for (BlockPos pos : todo) {
            BlockUtil.Result r = BlockUtil.place(pos, slot, true, 92);
            if (r != BlockUtil.Result.FAILED) return; // placed, or turning toward it
        }
    }

    @Override public String getInfo() { return threatened.isEmpty() ? null : String.valueOf(threatened.size()); }
}
