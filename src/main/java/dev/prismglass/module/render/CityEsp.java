package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Highlights the surround blocks of nearby enemies that can be "citied": a block beside their feet that can be
 * mined (not bedrock), where breaking it opens a crystal spot touching them, either in the gap itself (obsidian or
 * bedrock under it) or one block further out. Best = the one closest to you, drawn brighter.
 */
public class CityEsp extends Module {
    public final NumberSetting range = num("Range", 10, 3, 20, 1, "Enemies this close.");
    public final ColorSetting color = color("Color", 0x50FF4D8A, "Cityable blocks.");
    public final ColorSetting best = color("Best", 0x90FF2050, "The closest cityable block.");
    public final BoolSetting onlyHoled = bool("OnlyInHole", true, "Only enemies standing in a hole (fully surrounded).");

    private final List<BlockPos> blocks = new ArrayList<>();
    private BlockPos closest;
    private int ticks;

    public CityEsp() { super("CityEsp", "Highlights enemies' surround blocks you can break to crystal them.", Category.RENDER); }

    @Override public void onDisable() { blocks.clear(); closest = null; }

    @Override
    public void onTick() {
        if (ticks++ % 4 != 0) return;
        blocks.clear();
        closest = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || Prism.friends().isFriend(p) || mc.player.distanceTo(p) > range.get()) continue;
            if (onlyHoled.get() && !EntityUtil.isInHole(p)) continue;
            BlockPos feet = p.blockPosition();
            for (Direction d : BlockUtil.HORIZONTALS) {
                BlockPos b = feet.relative(d);
                if (!cityable(b, d)) continue;
                blocks.add(b);
                double dist = mc.player.distanceToSqr(b.getCenter());
                if (dist < bestDist) { bestDist = dist; closest = b; }
            }
        }
    }

    /** Breakable surround block with a crystal spot in it or right behind it once it's gone. */
    private boolean cityable(BlockPos b, Direction d) {
        var state = mc.level.getBlockState(b);
        if (state.isAir() || BlockUtil.isUnbreakable(b) || state.getCollisionShape(mc.level, b).isEmpty()) return false;
        return crystalBase(b.below()) && mc.level.getBlockState(b.above()).isAir()
            || crystalBase(b.relative(d).below()) && mc.level.getBlockState(b.relative(d)).isAir() && mc.level.getBlockState(b.relative(d).above()).isAir();
    }

    private boolean crystalBase(BlockPos pos) {
        var s = mc.level.getBlockState(pos);
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK);
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        for (BlockPos b : blocks) {
            int c = b.equals(closest) ? best.color() : color.color();
            Render3D.box(new AABB(b), c, ColorUtil.withAlpha(c, 0xD0), true);
        }
    }

    @Override public String getInfo() { return blocks.isEmpty() ? null : String.valueOf(blocks.size()); }
}
