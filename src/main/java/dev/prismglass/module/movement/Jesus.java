package dev.prismglass.module.movement;

import net.minecraft.tags.FluidTags;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class Jesus extends Module {
    public final ModeSetting mode = mode("Mode", "Solid", "Solid = walk on liquids. Dolphin = bob up. [Grim-unsafe]", "Solid", "Dolphin");
    public final BoolSetting lava = bool("Lava", true, "Also lava.");

    public Jesus() { super("Jesus", "Walk on water.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mode.is("Dolphin") && (mc.player.isInWater() || (lava.get() && mc.player.isInLava())) && !mc.player.isShiftKeyDown()) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.11, mc.player.getDeltaMovement().z);
        }
    }

    /** Shape for a fluid block, or null for vanilla. */
    public VoxelShape shape(BlockState state, BlockPos pos, CollisionContext ctx) {
        if (!mode.is("Solid") || mc.player == null) return null;
        if (!(ctx instanceof EntityCollisionContext ec) || ec.getEntity() != mc.player) return null;
        if (!lava.get() && state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return null;
        if (mc.player.isShiftKeyDown() || mc.player.isInWater() || mc.player.fallDistance > 3) return null;
        if (mc.player.getY() < pos.getY() + 0.9) return null;
        return Shapes.box(0, 0, 0, 1, 0.9, 1);
    }

    @Override public String getInfo() { return mode.get(); }
}
