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
import java.util.ArrayList;
import java.util.List;

/** Traps the nearest enemy: walls at feet and head, then a roof. Builds bottom-up so every block has support. */
public class AutoTrap extends Module {
    public final NumberSetting range = num("TargetRange", 5, 1, 8, 0.5, "Enemy range.");
    public final BoolSetting feet = bool("Feet", false, "Also trap feet (surround them).");
    public final BoolSetting roof = bool("Roof", true, "Cover their head.");

    public AutoTrap() { super("AutoTrap", "Traps enemies in obsidian.", Category.COMBAT); }

    @Override
    public void onTick() {
        Player t = EntityUtil.closestEnemy(range.get());
        if (t == null) return;
        Hud.setTarget(t);
        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        if (slot == -1) return;
        BlockPos f = t.blockPosition();
        List<BlockPos> list = new ArrayList<>();
        for (Direction d : BlockUtil.HORIZONTALS) {
            if (feet.get()) list.add(f.relative(d));
            list.add(f.relative(d).above());
        }
        if (roof.get()) { list.add(f.above(2).relative(Direction.NORTH)); list.add(f.above(2)); }
        for (BlockPos pos : list) {
            if (!BlockUtil.isReplaceable(pos) || BlockUtil.entityBlocks(pos, true)) continue;
            if (BlockUtil.getPlaceSide(pos) == null && BlockUtil.canPlace(pos.below())) pos = pos.below();
            if (BlockUtil.place(pos, slot, true, 80) == BlockUtil.Result.WAITING) return;
        }
    }
}
