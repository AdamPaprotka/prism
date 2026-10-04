package dev.prismglass.module.render;


import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;

/** Highlights safe holes: green = all bedrock, yellow = mixed obsidian/bedrock. */
public class HoleESP extends Module {
    public final NumberSetting range = num("Range", 8, 2, 16, 1, "Horizontal scan range.");
    public final NumberSetting height = num("Height", 0.6, 0, 1, 0.05, "Fade-up height of the plate.");
    public final ColorSetting bedrock = color("Bedrock", 0x6650FF8C, "Bedrock hole colour.");
    public final ColorSetting obsidian = color("Obsidian", 0x66FFD24D, "Obsidian hole colour.");
    public final BoolSetting doubles = bool("Doubles", true, "Show 2x1 holes.");
    public final BoolSetting onlyBases = bool("OnlyBases", false, "Only in chunks BaseFinder flagged.");

    private record Hole(AABB box, boolean safe) {}
    private final List<Hole> holes = new ArrayList<>();
    private int ticks;

    public HoleESP() { super("HoleESP", "Shows safe holes.", Category.RENDER); }

    @Override
    public void onTick() {
        if (ticks++ % 5 != 0) return;
        holes.clear();
        BlockPos center = mc.player.blockPosition();
        int r = range.getInt();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -4, -r), center.offset(r, 3, r))) {
            if (!BlockUtil.isReplaceable(pos) || !BlockUtil.isReplaceable(pos.above()) || !BlockUtil.isReplaceable(pos.above(2))) continue;
            if (onlyBases.get() && !dev.prismglass.module.world.BaseFinder.allows(pos)) continue;
            int kind = holeKind(pos, null);
            if (kind > 0) { holes.add(new Hole(new AABB(pos), kind == 2)); continue; }
            if (!doubles.get()) continue;
            for (Direction d : new Direction[]{Direction.EAST, Direction.SOUTH}) {
                BlockPos other = pos.relative(d);
                if (!BlockUtil.isReplaceable(other) || !BlockUtil.isReplaceable(other.above())) continue;
                int k1 = holeKind(pos, d), k2 = holeKind(other, d.getOpposite());
                if (k1 > 0 && k2 > 0) holes.add(new Hole(new AABB(pos).minmax(new AABB(other)), k1 == 2 && k2 == 2));
            }
        }
    }

    /** 0 = not a hole, 1 = blast-proof mixed, 2 = all bedrock. {@code open} side is ignored. */
    private int holeKind(BlockPos pos, Direction open) {
        boolean allBedrock = true;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            if (d == open) continue;
            BlockPos n = pos.relative(d);
            if (!BlockUtil.isBlastProof(n)) return 0;
            if (!BlockUtil.state(n).is(Blocks.BEDROCK)) allBedrock = false;
        }
        return allBedrock ? 2 : 1;
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        for (Hole h : holes) Render3D.plate(h.box(), h.safe() ? bedrock.color() : obsidian.color(), height.get(), false);
    }

    @Override public String getInfo() { return String.valueOf(holes.size()); }
}
