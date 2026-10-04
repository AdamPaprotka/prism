package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** Flags chunks with lots of storage blocks while you travel, logs them to chat and prism/stashes.txt. */
public class StashFinder extends Module {
    public final NumberSetting minStorage = num("MinStorage", 8, 1, 100, 1, "Storage blocks in a chunk to count as a stash.");
    public final BoolSetting shulkersCount = bool("ShulkersDouble", true, "Shulker boxes count twice.");
    private final Set<Long> checked = new HashSet<>();
    private int found;

    public StashFinder() { super("StashFinder", "Finds stashes as you fly over them.", Category.WORLD); }

    @Override public void onEnable() { checked.clear(); found = 0; }

    @Override
    public void onTick() {
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        int r = mc.options.renderDistance().get();
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
            long key = ChunkPos.pack(x, z);
            if (checked.contains(key)) continue;
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            checked.add(key);
            int count = 0;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof ShulkerBoxBlockEntity) count += shulkersCount.get() ? 2 : 1;
                else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof EnderChestBlockEntity
                    || be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity) count++;
            }
            if (count >= minStorage.getInt()) report(x, z, count);
        }
    }

    private void report(int cx, int cz, int count) {
        found++;
        String line = String.format("Stash: %d storage at %d %d (%s)", count, cx * 16 + 8, cz * 16 + 8, mc.level.dimension().identifier().getPath());
        ChatUtil.good(line);
        try {
            Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("prism").resolve("stashes.txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    @Override public String getInfo() { return String.valueOf(found); }
}
