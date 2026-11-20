package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.phys.AABB;

/**
 * Slime chunks from the world seed (.seed or SeedCracker), with vanilla's own check: a chunk is one when
 * {@code seedSlimeChunk(x, z, seed, 987234911).nextInt(10) == 0}. Slimes spawn in them below y 40 at any light level.
 */
public class SlimeChunks extends Module {
    public final NumberSetting range = num("Range", 8, 2, 32, 1, "Chunks around you.");
    public final ModeSetting height = mode("Height", "Feet", "Where the squares are drawn: at your feet, or at y 39 (slimes spawn at y 39 and below).", "Feet", "Y39");
    public final ColorSetting color = color("Color", 0xFF5CD65C, "Slime chunk colour.");
    public final NumberSetting alpha = num("Alpha", 60, 0, 255, 1, "Square fill opacity.");
    public final BoolSetting throughWalls = bool("ThroughWalls", true, "Draw through blocks.");

    public SlimeChunks() { super("SlimeChunks", "Shows slime chunks from the world seed (.seed or SeedCracker).", Category.RENDER); }

    /** Vanilla's slime chunk rule (Slime.checkSlimeSpawnRules). */
    public static boolean isSlimeChunk(long seed, int chunkX, int chunkZ) {
        return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, 987234911L).nextInt(10) == 0;
    }

    private Long seed() {
        String s = Prism.modules().get(Xray.class).seed.get().trim();
        return s.isEmpty() ? null : Xray.parseSeed(s);
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        Long seed = seed();
        if (seed == null || mc.level.dimension() != Level.OVERWORLD) return;
        int r = range.getInt();
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        double y = height.is("Y39") ? 39 : Math.floor(mc.player.getY()) + 0.02;
        int fill = ColorUtil.withAlpha(color.color(), alpha.getInt());
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
            if (!isSlimeChunk(seed, x, z)) continue;
            AABB square = new AABB(x << 4, y, z << 4, (x << 4) + 16, y, (z << 4) + 16);
            Render3D.plate(square, fill, 0.6, throughWalls.get());
        }
    }

    @Override
    public String getInfo() {
        if (seed() == null) return "no seed";
        return mc.level != null && mc.level.dimension() != Level.OVERWORLD ? "overworld only" : null;
    }
}
