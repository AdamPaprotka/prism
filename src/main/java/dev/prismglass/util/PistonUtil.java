package dev.prismglass.util;

import dev.prismglass.Prism;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Piston helpers for PistonAura / PistonPush. A placed piston faces back toward you: the server takes the dominant
 * axis of the rotation it last received (Direction.orderedByNearest) and flips it. BlockUtil.place aims the silent
 * rotation at the face it clicks, so where that face is decides the facing, and we can work it out beforehand.
 */
public final class PistonUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private PistonUtil() {}

    /** The point BlockUtil.place would click to put a block at {@code pos}, or null when it can't. */
    public static Vec3 clickPoint(BlockPos pos) {
        Direction side = BlockUtil.getPlaceSide(pos);
        if (side == null) return null;
        BlockPos neighbor = pos.relative(side);
        Direction face = side.getOpposite();
        return Vec3.atCenterOf(neighbor).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
    }

    /** The facing a piston placed at {@code pos} right now would get, or null when it can't be placed. */
    public static Direction facingIfPlaced(BlockPos pos) {
        Vec3 click = clickPoint(pos);
        if (click == null) return null;
        Vec3 look = click.subtract(mc.player.getEyePosition());
        return Direction.getApproximateNearest(look.x, look.y, look.z).getOpposite();
    }

    public static boolean isPiston(BlockPos pos) { return BlockUtil.state(pos).getBlock() instanceof PistonBaseBlock; }

    public static Direction facing(BlockPos pos) { return BlockUtil.state(pos).getValue(PistonBaseBlock.FACING); }

    public static boolean extended(BlockPos pos) { return BlockUtil.state(pos).getValue(PistonBaseBlock.EXTENDED); }

    /**
     * Breaks a block that breaks in one hit (torches, buttons...) once the silent rotation faces it, through the
     * visible face (Grim PositionBreak). Returns true when the break was sent.
     */
    public static boolean breakInstant(BlockPos pos, int priority) {
        Prism.rotations().request(Vec3.atCenterOf(pos), priority);
        if (!Prism.rotations().isFacing(new AABB(pos), Prism.anticheat().placeRange.get())) return false;
        Direction face = BlockUtil.breakFace(pos);
        if (face == null) face = Direction.UP;
        mc.gameMode.startDestroyBlock(pos, face);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
