package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Finds blocks by id (comma separated, e.g. "ancient_debris,diamond_ore"). Section palettes are checked first, so scans stay cheap. */
public class Search extends Module {
    public final ListSetting blocks = list("Blocks", "ancient_debris,diamond_ore,deepslate_diamond_ore,spawner", "Block ids to find.", ListSetting.Kind.BLOCK);
    public final NumberSetting range = num("ChunkRange", 4, 1, 12, 1, "Chunks around you.");
    public final ColorSetting color = color("Color", 0xFF4DD2FF, "Highlight colour.");
    public final BoolSetting tracers = bool("Tracers", false, "Lines to found blocks.");
    public final BoolSetting onlyBases = bool("OnlyBases", false, "Only in chunks BaseFinder flagged.");

    private final List<BlockPos> found = new ArrayList<>();
    private String lastQuery = "";
    private Set<Block> targets = Set.of();
    private int ticks;

    public Search() { super("Search", "Highlights chosen blocks.", Category.RENDER); }

    @Override
    public void onTick() {
        if (!blocks.get().equals(lastQuery)) {
            lastQuery = blocks.get();
            Set<Block> set = new HashSet<>();
            for (String id : lastQuery.split(",")) {
                Identifier ident = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
                if (ident != null && BuiltInRegistries.BLOCK.containsKey(ident)) set.add(BuiltInRegistries.BLOCK.getValue(ident));
            }
            targets = set;
            ticks = 0;
        }
        if (ticks++ % 40 != 0 || targets.isEmpty()) return;
        found.clear();
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z(), r = range.getInt();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> targets.contains(st.getBlock()))) continue;
                    int baseY = chunk.getSectionYFromSectionIndex(i) * 16;
                    for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++) {
                        if (targets.contains(s.getBlockState(bx, by, bz).getBlock())) {
                            found.add(new BlockPos(x * 16 + bx, baseY + by, z * 16 + bz));
                            if (found.size() > 2000) return;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        int c = color.color();
        for (BlockPos p : found) {
            if (onlyBases.get() && !dev.prismglass.module.world.BaseFinder.allows(p)) continue;
            Render3D.box(new AABB(p), ColorUtil.withAlpha(c, 40), c, true);
            if (tracers.get()) Render3D.tracer(Vec3.atCenterOf(p), ColorUtil.withAlpha(c, 160));
        }
    }

    @Override public String getInfo() { return String.valueOf(found.size()); }
}
