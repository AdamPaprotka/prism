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
import net.minecraft.world.phys.Vec3;

/** Drops obsidian behind you when an enemy chases you, blocking the path (tunnels/bridges). */
public class Blocker extends Module {
    public final NumberSetting range = num("EnemyRange", 6, 2, 12, 0.5, "Only when an enemy is this close.");

    public Blocker() { super("Blocker", "Blocks pursuers behind you.", Category.ANARCHY); }

    @Override
    public void onTick() {
        Player t = EntityUtil.closestEnemy(range.get());
        if (t == null) return;
        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.COBBLESTONE, Blocks.NETHERRACK);
        if (slot == -1) return;
        Vec3 toEnemy = t.position().subtract(mc.player.position());
        Direction d = Direction.getApproximateNearest(toEnemy.x, 0, toEnemy.z);
        BlockPos feet = mc.player.blockPosition().relative(d);
        for (BlockPos p : new BlockPos[]{feet, feet.above()}) {
            if (!BlockUtil.isReplaceable(p) || BlockUtil.entityBlocks(p, false)) continue;
            if (BlockUtil.place(p, slot, true, 85) == BlockUtil.Result.WAITING) return;
        }
    }
}
