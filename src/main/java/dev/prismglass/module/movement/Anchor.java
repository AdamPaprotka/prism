package dev.prismglass.module.movement;


import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pulls you into holes you walk over (centre + drop). */
public class Anchor extends Module {
    public final NumberSetting depth = num("Depth", 3, 1, 6, 1, "How far below to look for a hole.");
    public final NumberSetting pull = num("Pull", 0.3, 0.05, 1, 0.05, "Downward pull speed.");

    public Anchor() { super("Anchor", "Snap into holes below you. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var p = mc.player;
        if (p.onGround() || mc.player.input.keyPresses.jump()) return;
        BlockPos feet = p.blockPosition();
        for (int i = 0; i <= depth.getInt(); i++) {
            BlockPos pos = feet.below(i);
            if (!BlockUtil.isReplaceable(pos)) return;
            if (isHole(pos)) {
                double cx = pos.getX() + 0.5, cz = pos.getZ() + 0.5;
                p.setDeltaMovement((cx - p.getX()) * 0.5, -pull.get(), (cz - p.getZ()) * 0.5);
                return;
            }
        }
    }

    private static boolean isHole(BlockPos pos) {
        if (BlockUtil.isReplaceable(pos.below())) return false;
        for (Direction d : BlockUtil.HORIZONTALS) if (BlockUtil.isReplaceable(pos.relative(d))) return false;
        return BlockUtil.isReplaceable(pos.above());
    }
}
