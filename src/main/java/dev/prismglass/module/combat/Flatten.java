package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * Crystal-PvP floor maker: fills the gaps (air, water, lava, grass...) in the floor layer around you, and around
 * your target with Target on, with obsidian. A flat obsidian floor means a crystal can go anywhere and there are no
 * holes for them to drop into. Your own hole and the blocks you stand on are left alone. One block per tick,
 * closest first.
 */
public class Flatten extends Module {
    public final NumberSetting radius = num("Radius", 3, 1, 5, 1, "Floor radius around the centre.");
    public final BoolSetting target = bool("Target", false, "Flatten around the nearest enemy instead of you.");
    public final NumberSetting targetRange = num("TargetRange", 8, 3, 12, 0.5, "Target: enemies this close.").visibleWhen(() -> target.get());
    public final BoolSetting skipHoles = bool("SkipHoles", true, "Don't fill safe holes (yours or anyone's).");

    private int filled;

    public Flatten() { super("Flatten", "Fills the floor around you (or a target) with obsidian for crystal spots.", Category.COMBAT); }

    @Override public void onEnable() { filled = 0; }

    @Override
    public void onTick() {
        BlockPos centre = BlockUtil.playerPos();
        if (target.get()) {
            Player t = EntityUtil.closestEnemy(targetRange.get());
            if (t == null) return;
            centre = t.blockPosition();
        }
        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        if (slot == -1) return;
        int r = radius.getInt();
        BlockPos myFeet = BlockUtil.playerPos();
        List<BlockPos> spots = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            BlockPos floor = centre.offset(dx, -1, dz);
            if (!BlockUtil.isReplaceable(floor) || !BlockUtil.inPlaceRange(floor)) continue;
            if (floor.equals(myFeet.below())) continue;
            if (skipHoles.get() && isHole(floor.above())) continue;
            spots.add(floor);
        }
        spots.sort(Comparator.comparingDouble(p -> mc.player.distanceToSqr(p.getCenter())));
        for (BlockPos p : spots) {
            BlockUtil.Result res = BlockUtil.place(p, slot, true, 70);
            if (res == BlockUtil.Result.PLACED) filled++;
            if (res != BlockUtil.Result.FAILED) return;
        }
    }

    /** A 1x1 pit with blast-proof sides (filling its floor would ruin it). */
    private boolean isHole(BlockPos pos) {
        for (var d : BlockUtil.HORIZONTALS) if (!BlockUtil.isBlastProof(pos.relative(d))) return false;
        return true;
    }

    @Override public String getInfo() { return filled == 0 ? null : String.valueOf(filled); }
}
