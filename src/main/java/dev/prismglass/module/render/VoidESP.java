package dev.prismglass.module.render;


import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;

/** Shows gaps in the bedrock floor (and nether roof) you could fall through. */
public class VoidESP extends Module {
    public final NumberSetting range = num("Range", 12, 4, 32, 1, "Horizontal range.");
    public final ColorSetting color = color("Color", 0x88FF3355, "Hole colour.");
    private final List<BlockPos> holes = new ArrayList<>();
    private int ticks;

    public VoidESP() { super("VoidESP", "Shows holes in the bedrock floor.", Category.RENDER); }

    @Override
    public void onTick() {
        if (ticks++ % 20 != 0) return;
        holes.clear();
        int bottom = mc.level.getMinY();
        if (mc.player.getY() - bottom > 64) return;
        BlockPos c = mc.player.blockPosition();
        int r = range.getInt();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            BlockPos floor = new BlockPos(c.getX() + x, bottom, c.getZ() + z);
            if (!BlockUtil.state(floor).is(Blocks.BEDROCK)) holes.add(floor);
        }
        if (mc.level.dimension() == Level.NETHER) {
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                BlockPos roof = new BlockPos(c.getX() + x, 127, c.getZ() + z);
                if (!BlockUtil.state(roof).is(Blocks.BEDROCK) && Math.abs(mc.player.getY() - 127) < 32) holes.add(roof);
            }
        }
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        for (BlockPos p : holes) Render3D.plate(new AABB(p), color.color(), 0.4, true);
    }

    @Override public String getInfo() { return String.valueOf(holes.size()); }
}
