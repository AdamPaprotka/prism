package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.Render3D;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Light-level overlay: marks every spot around you where a monster could spawn. Since 1.18 hostiles need block
 * light 0, a spawnable floor (isValidSpawn for a zombie: solid top, not bedrock/glass/slabs...) and room to stand.
 * Red = sky light 0 too, so mobs spawn there any time; yellow = only once it's dark (night, storms).
 */
public class Lighting extends Module {
    public final NumberSetting radius = num("Radius", 16, 4, 32, 1, "Horizontal radius.");
    public final NumberSetting vertical = num("Vertical", 8, 2, 24, 1, "Blocks above and below you.");
    public final BoolSetting night = bool("NightOnly", true, "Also show spots that only spawn mobs at night (yellow).");
    public final ColorSetting always = color("Always", 0xD0FF4040, "Spawns any time.");
    public final ColorSetting dark = color("AtNight", 0xA0FFD040, "Spawns only in the dark.");

    private record Spot(int x, int y, int z, boolean always) {}

    private final List<Spot> spots = new ArrayList<>();
    private int ticks;

    public Lighting() { super("Lighting", "Light level overlay: where mobs can spawn.", Category.RENDER); }

    @Override public void onDisable() { spots.clear(); }

    @Override
    public void onTick() {
        if (ticks++ % 10 != 0) return;
        spots.clear();
        var level = mc.level;
        BlockPos c = mc.player.blockPosition();
        int r = radius.getInt(), v = vertical.getInt();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos(), above = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (x * x + z * z > r * r) continue;
            for (int y = -v; y <= v; y++) {
                pos.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                var state = level.getBlockState(pos);
                if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) continue;
                below.setWithOffset(pos, 0, -1, 0);
                if (!level.getBlockState(below).isValidSpawn(level, below, EntityType.ZOMBIE)) continue;
                above.setWithOffset(pos, 0, 1, 0);
                if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) continue;
                if (level.getBrightness(LightLayer.BLOCK, pos) > 0) continue;
                boolean noSky = level.getBrightness(LightLayer.SKY, pos) == 0;
                if (!noSky && !night.get()) continue;
                spots.add(new Spot(pos.getX(), pos.getY(), pos.getZ(), noSky));
            }
        }
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        for (Spot s : spots) {
            int col = s.always() ? always.color() : dark.color();
            double y = s.y() + 0.02;
            Render3D.line(new Vec3(s.x() + 0.2, y, s.z() + 0.2), new Vec3(s.x() + 0.8, y, s.z() + 0.8), col);
            Render3D.line(new Vec3(s.x() + 0.8, y, s.z() + 0.2), new Vec3(s.x() + 0.2, y, s.z() + 0.8), col);
        }
    }

    /** Spots that spawn mobs any time (no sky light either). */
    public int alwaysCount() {
        int n = 0;
        for (Spot s : spots) if (s.always()) n++;
        return n;
    }

    @Override public String getInfo() { return spots.isEmpty() ? null : String.valueOf(spots.size()); }
}
