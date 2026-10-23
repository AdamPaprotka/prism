package dev.prismglass.module.combat;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;

/**
 * Anvil burrow: drops an anvil on yourself while you stand in a hole. A falling anvil passes through entities and
 * lands in the lowest free block, your feet, so you end up standing inside an anvil: crystals can't be placed at
 * your feet and nobody can step into your hole. You take a little fall damage from it (2 per block fallen). Needs
 * something to place the anvil against two blocks over your head: builds a support from your walls if needed.
 * Turns itself off once the anvil is in your feet.
 */
public class SelfAnvil extends Module {
    public final NumberSetting height = num("Height", 2, 2, 4, 1, "Blocks above your head to drop it from (more = more damage to you).");
    public final BoolSetting onlyInHole = bool("OnlyInHole", true, "Only when you're in a hole.");
    public final NumberSetting supports = num("Supports", 2, 0, 3, 1, "Support blocks it may place to hold the anvil up there.");

    public SelfAnvil() { super("SelfAnvil", "Drops an anvil into your own feet (anvil burrow).", Category.COMBAT); }

    @Override
    public void onTick() {
        var p = mc.player;
        BlockPos feet = BlockUtil.playerPos();
        if (BlockUtil.state(feet).is(BlockTags.ANVIL)) { ChatUtil.good("SelfAnvil: burrowed"); setEnabled(false); return; }
        if (onlyInHole.get() && !EntityUtil.isInHole(p)) return;
        BlockPos drop = feet.above(height.getInt() + 1);
        // the anvil's way down has to be clear apart from us
        for (BlockPos q = feet.above(2); q.getY() < drop.getY(); q = q.above()) if (!BlockUtil.isReplaceable(q)) return;
        int anvil = InvUtil.findHotbar(s -> s.is(net.minecraft.tags.ItemTags.ANVIL));
        if (anvil == -1) { ChatUtil.error("SelfAnvil: no anvil in your hotbar"); setEnabled(false); return; }
        List<BlockPos> chain = BlockUtil.supportChain(drop, supports.getInt(), q -> q.getX() != feet.getX() || q.getZ() != feet.getZ());
        if (chain.isEmpty()) return;
        BlockPos next = chain.get(0);
        if (next.equals(drop)) { BlockUtil.place(drop, anvil, true, 90); return; }
        int block = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.COBBLESTONE, Blocks.NETHERRACK);
        if (block != -1) BlockUtil.place(next, block, true, 90);
    }
}
