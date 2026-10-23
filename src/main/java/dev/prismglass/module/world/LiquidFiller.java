package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Set;

/**
 * Places blocks into lava / water around you: source blocks only by default (that's what stops a flow), nearest
 * first, one a tick through the normal place path (visible face, rotation, swap back), so it's Grim-safe.
 */
public class LiquidFiller extends Module {
    public final ModeSetting liquid = mode("Liquid", "Lava", "Which liquid to fill.", "Lava", "Water", "Both");
    public final BoolSetting sourcesOnly = bool("SourcesOnly", true, "Only source blocks (flowing ones go away on their own).");
    public final NumberSetting range = num("Range", 4.5, 1, 6, 0.5, "Place range (capped by AntiCheat place range).");
    public final ListSetting blocks = list("Blocks", "netherrack,cobblestone,cobbled_deepslate,stone,dirt,blackstone", "Blocks to fill with (first one you have).", ListSetting.Kind.BLOCK);
    public final BoolSetting notUnderMe = bool("NotUnderMe", false, "Skip the liquid you stand in (it'd push you up).");

    private Set<net.minecraft.world.item.Item> parsed = Set.of();
    private String lastList = "";

    public LiquidFiller() { super("LiquidFiller", "Fills lava / water source blocks around you.", Category.WORLD); }

    @Override
    public void onTick() {
        if (!lastList.equals(blocks.get())) {
            lastList = blocks.get();
            Set<net.minecraft.world.item.Item> s = new HashSet<>();
            for (String id : lastList.split(",")) {
                Identifier i = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
                if (i != null && BuiltInRegistries.BLOCK.containsKey(i)) s.add(BuiltInRegistries.BLOCK.getValue(i).asItem());
            }
            parsed = s;
        }
        int slot = InvUtil.findHotbar(st -> st.getItem() instanceof BlockItem && parsed.contains(st.getItem()));
        if (slot == -1 || !BlockUtil.budgetLeft()) return;
        double r = Prism.anticheat().placeRange(range.get());
        int ri = (int) Math.ceil(r);
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(mc.player.blockPosition().offset(-ri, -ri, -ri), mc.player.blockPosition().offset(ri, ri, ri))) {
            if (!wanted(p) || RotationUtil.distanceToBox(eye, new AABB(p)) > r) continue;
            if (notUnderMe.get() && mc.player.getBoundingBox().intersects(new AABB(p))) continue;
            if (BlockUtil.getPlaceSide(p) == null) continue;
            double d = eye.distanceToSqr(p.getCenter());
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        if (best != null) BlockUtil.place(best, slot, true, 40);
    }

    private boolean wanted(BlockPos p) {
        FluidState f = mc.level.getFluidState(p);
        if (f.isEmpty() || (sourcesOnly.get() && !f.isSource()) || !BlockUtil.isReplaceable(p)) return false;
        boolean lava = f.is(FluidTags.LAVA), water = f.is(FluidTags.WATER);
        return switch (liquid.get()) {
            case "Lava" -> lava;
            case "Water" -> water;
            default -> lava || water;
        };
    }
}
