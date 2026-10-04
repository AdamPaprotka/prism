package dev.prismglass.module.anarchy;

import net.minecraft.client.multiplayer.chat.report.Report.Result;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * Tunnel fights (1x2 highway/nether tunnels): when an enemy is in a tunnel, seal the tunnel on both
 * sides of them with obsidian, then let your auras finish them. Uses the normal placement pipeline.
 */
public class TunnelKill extends Module {
    public final NumberSetting range = num("Range", 5, 2, 8, 0.5, "Enemy range.");
    public final BoolSetting roof = bool("Roof", false, "Also block their head if open.");

    public TunnelKill() { super("TunnelKill", "Seals enemies inside 1x2 tunnels.", Category.ANARCHY); }

    @Override
    public void onTick() {
        Player t = EntityUtil.closestEnemy(range.get());
        if (t == null) return;
        BlockPos f = t.blockPosition();
        Direction axis = tunnelAxis(f);
        if (axis == null) return;
        Hud.setTarget(t);
        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        if (slot == -1) return;
        BlockPos[] spots = {f.relative(axis), f.relative(axis).above(), f.relative(axis.getOpposite()), f.relative(axis.getOpposite()).above(), f.above(2)};
        for (int i = 0; i < spots.length; i++) {
            if (i == 4 && !roof.get()) break;
            BlockPos p = spots[i];
            if (!BlockUtil.isReplaceable(p) || BlockUtil.entityBlocks(p, true)) continue;
            if (BlockUtil.place(p, slot, true, 85) == BlockUtil.Result.WAITING) return;
        }
    }

    /** Direction the tunnel runs along, or null if the target isn't in a 1-wide tunnel. */
    private static Direction tunnelAxis(BlockPos f) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST}) {
            Direction side = d.getClockWise();
            boolean walls = !BlockUtil.isReplaceable(f.relative(side)) && !BlockUtil.isReplaceable(f.relative(side.getOpposite()))
                && !BlockUtil.isReplaceable(f.above().relative(side)) && !BlockUtil.isReplaceable(f.above().relative(side.getOpposite()));
            if (walls) return d;
        }
        return null;
    }
}
