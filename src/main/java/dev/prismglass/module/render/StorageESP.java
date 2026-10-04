package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;

public class StorageESP extends Module {
    public final NumberSetting range = num("ChunkRange", 6, 1, 16, 1, "Chunks around you to scan.");
    public final ColorSetting chest = color("Chest", 0xFFFFB84D, "Chests and barrels.");
    public final ColorSetting ender = color("EnderChest", 0xFFB197FC, "Ender chests.");
    public final ColorSetting shulker = color("Shulker", 0xFFFF6BCB, "Shulker boxes.");
    public final ColorSetting other = color("Other", 0xFF8CE99A, "Furnaces, hoppers, dispensers...");
    public final BoolSetting spawners = bool("Spawners", true, "Mob spawners.");
    public final ColorSetting spawnerColor = color("SpawnerColor", 0xFFFF6B6B, "Spawner colour.");
    public final BoolSetting onlyBases = bool("OnlyBases", false, "Only in chunks BaseFinder flagged.");
    public final NumberSetting fillAlpha = num("FillAlpha", 35, 0, 255, 1, "Fill opacity.");

    private record Entry(AABB box, int color) {}
    private final List<Entry> found = new ArrayList<>();
    private int ticks;

    public StorageESP() { super("StorageESP", "Highlights storage blocks.", Category.RENDER); }

    @Override
    public void onTick() {
        if (ticks++ % 10 != 0) return;
        found.clear();
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z(), r = range.getInt();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    int c;
                    if (be instanceof EnderChestBlockEntity) c = ender.color();
                    else if (be instanceof ShulkerBoxBlockEntity) c = shulker.color();
                    else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) c = chest.color();
                    else if (be instanceof AbstractFurnaceBlockEntity || be instanceof HopperBlockEntity
                        || be instanceof DispenserBlockEntity) c = other.color();
                    else if (be instanceof SpawnerBlockEntity && spawners.get()) c = spawnerColor.color();
                    else continue;
                    if (onlyBases.get() && !dev.prismglass.module.world.BaseFinder.allows(be.getBlockPos())) continue;
                    found.add(new Entry(new AABB(be.getBlockPos()).deflate(0.06), c));
                }
            }
        }
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        for (Entry e : found) Render3D.box(e.box(), ColorUtil.withAlpha(e.color(), fillAlpha.getInt()), e.color(), true);
    }

    @Override public String getInfo() { return String.valueOf(found.size()); }
}
