package dev.prismglass.module.combat;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.BlockUtil;
import dev.prismglass.Prism;
import dev.prismglass.manager.MovementHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Stops anvils dropped on your head: when a falling anvil is above you (or an anvil block sits over your column),
 * places a block in the free space right above your head so it lands on that instead. Above a hole there's usually
 * nothing to place against up there, so it builds out from the surround: a head-high wall beside you, a block on
 * top of that wall, then the roof against that block's side (one block per tick). With StrictDirection (Grim's
 * PositionPlace: only faces you can see) the wall's top is above your eye while you stand in the hole, so it jumps
 * straight up to see it. A falling anvil from 4+ blocks gives about a dozen ticks: enough for that.
 */
public class AntiAnvil extends Module {
    public final NumberSetting height = num("Height", 14, 2, 30, 1, "React to anvils this many blocks above your head.");
    public final BoolSetting placedAnvils = bool("PlacedAnvils", true, "Also react to anvil blocks already hanging above you (about to drop).");
    public final BoolSetting jump = bool("Jump", true, "With StrictDirection, jump (in place) when the block tops you'd build on are above your eye.");

    private int busy;
    /** Where we stand (kept while a jump lifts us, so the roof stays two blocks over our real head). */
    private BlockPos stand;

    public AntiAnvil() { super("AntiAnvil", "Puts a block over your head when an anvil is falling on you.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (busy > 0) busy--;
        if (mc.player.onGround() || stand == null) stand = mc.player.blockPosition();
        boolean danger = threatened();
        if (!danger && busy == 0) return;
        if (danger) busy = 10; // keep finishing the roof for a moment after the anvil is gone
        BlockPos head = stand.above(), roof = stand.above(2);
        if (!BlockUtil.isReplaceable(roof)) { busy = 0; return; }
        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK, Blocks.ENDER_CHEST, Blocks.COBBLESTONE, Blocks.NETHERRACK);
        if (slot == -1) return;
        if (BlockUtil.getPlaceSide(roof) != null) { BlockUtil.place(roof, slot, true, 95); return; }

        // 1) a head-high wall beside us to build on (placed on top of the surround, visible from where we stand)
        Direction side = null;
        for (Direction d : BlockUtil.HORIZONTALS) if (!BlockUtil.isReplaceable(head.relative(d))) { side = d; break; }
        if (side == null) {
            for (Direction d : BlockUtil.HORIZONTALS) if (BlockUtil.place(head.relative(d), slot, true, 95) != BlockUtil.Result.FAILED) return;
            return;
        }
        // 2) the block above that wall, level with the roof. Its support is the wall's top face, which is above our
        // eye while we stand in the hole: with StrictDirection only a jump (straight up, still in the hole) shows it
        BlockPos support = roof.relative(side);
        if (BlockUtil.isReplaceable(support)) {
            boolean visible = !Prism.anticheat().strictDirection.get() || mc.player.getEyeY() > support.getY();
            if (visible) BlockUtil.place(support, slot, true, 95);
            else {
                // aim at the wall's top already, so the block goes down on the first tick of the jump that shows it
                Prism.rotations().request(Vec3.atCenterOf(support).add(0, -0.5, 0), 95);
                if (jump.get() && mc.player.onGround()) MovementHooks.requestJump();
            }
            return;
        }
        // 3) the roof against the support's side: aim there while falling back down
        Prism.rotations().request(Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(side.getOpposite().getUnitVec3i()).scale(0.5)), 95);
    }

    private boolean threatened() {
        var p = mc.player;
        double top = p.getBoundingBox().maxY;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof FallingBlockEntity fb) || !fb.getBlockState().is(BlockTags.ANVIL)) continue;
            if (Math.abs(fb.getX() - p.getX()) < 0.9 && Math.abs(fb.getZ() - p.getZ()) < 0.9 && fb.getY() > top - 0.1 && fb.getY() < top + height.get()) return true;
        }
        if (placedAnvils.get()) {
            BlockPos head = (stand != null ? stand : p.blockPosition()).above(2);
            for (int i = 0; i < height.getInt(); i++) {
                var s = mc.level.getBlockState(head.above(i));
                if (s.is(BlockTags.ANVIL)) return true;
                if (!s.isAir()) break; // something already in the way
            }
        }
        return false;
    }

    @Override public String getInfo() { return busy > 0 ? "Roofing" : null; }
}
