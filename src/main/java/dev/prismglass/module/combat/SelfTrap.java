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
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import java.util.List;

/** Surround + walls at head level + a roof. */
public class SelfTrap extends Module {
    public final BoolSetting roof = bool("Roof", true, "Place a block above your head.");

    public SelfTrap() { super("SelfTrap", "Traps yourself in obsidian.", Category.COMBAT); }

    @Override
    public void onTick() {
        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        if (slot == -1) return;
        List<BlockPos> list = new ArrayList<>(Surround.positions());
        for (BlockPos p : Surround.positions()) list.add(p.above());
        if (roof.get()) list.add(mc.player.blockPosition().above(2));
        for (BlockPos pos : list) {
            if (!BlockUtil.isReplaceable(pos)) continue;
            if (BlockUtil.place(pos, slot, true, 85) == BlockUtil.Result.WAITING) return;
        }
    }
}
