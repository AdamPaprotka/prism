package dev.prismglass.module.combat;

import net.minecraft.client.multiplayer.chat.report.Report.Result;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/** Fills safe holes near enemies so they can't hide in them. */
public class HoleFiller extends Module {
    public final NumberSetting targetRange = num("TargetRange", 4, 1, 8, 0.5, "Only fill holes this close to an enemy.");
    public final ModeSetting block = mode("Block", "Obsidian", "Fill material.", "Obsidian", "Web");

    public HoleFiller() { super("HoleFiller", "Fills holes near enemies.", Category.COMBAT); }

    @Override
    public void onTick() {
        Player t = EntityUtil.closestEnemy(10);
        if (t == null || EntityUtil.isInHole(t)) return;
        int slot = block.is("Web") ? BlockUtil.findBlock(Blocks.COBWEB) : BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        if (slot == -1) return;
        int r = (int) Math.ceil(Prism.anticheat().placeRange.get());
        BlockPos me = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(me.offset(-r, -2, -r), me.offset(r, 2, r))) {
            if (t.blockPosition().distSqr(pos) > targetRange.get() * targetRange.get()) continue;
            if (!isHole(pos) || pos.equals(mc.player.blockPosition())) continue;
            if (BlockUtil.place(pos.immutable(), slot, true, 70) != BlockUtil.Result.FAILED) return;
        }
    }

    private static boolean isHole(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || !BlockUtil.isReplaceable(pos.above()) || BlockUtil.isReplaceable(pos.below())) return false;
        for (Direction d : BlockUtil.HORIZONTALS) if (!BlockUtil.isBlastProof(pos.relative(d))) return false;
        return true;
    }
}
