package dev.prismglass.util;

import dev.prismglass.Prism;
import dev.prismglass.module.client.AntiCheat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BlockUtil {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int placedThisTick;

    public static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private BlockUtil() {}

    /** Called by the client each tick. */
    public static void resetBudget() { placedThisTick = 0; }

    public static boolean budgetLeft() {
        return placedThisTick < Prism.anticheat().placesPerTick.getInt() && Prism.guard().canPlace();
    }

    public static BlockState state(BlockPos pos) { return mc.level.getBlockState(pos); }

    /** 26.1.2's BlockState.blocksMotion() (removed in 26.3): legacy-solid, cobwebs and bamboo saplings excepted. */
    public static boolean blocksMotion(BlockState s) {
        return s.isSolid() && !s.is(Blocks.COBWEB) && !s.is(Blocks.BAMBOO_SAPLING);
    }

    public static boolean isReplaceable(BlockPos pos) { return state(pos).canBeReplaced(); }

    public static boolean isBlastProof(BlockPos pos) {
        BlockState s = state(pos);
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK) || s.is(Blocks.CRYING_OBSIDIAN)
            || s.is(Blocks.ENDER_CHEST) || s.is(Blocks.RESPAWN_ANCHOR) || s.is(Blocks.NETHERITE_BLOCK)
            || s.is(Blocks.ANCIENT_DEBRIS) || s.is(Blocks.ENCHANTING_TABLE) || s.is(Blocks.ANVIL)
            || s.is(Blocks.REINFORCED_DEEPSLATE);
    }

    public static boolean isUnbreakable(BlockPos pos) {
        BlockState s = state(pos);
        return s.is(Blocks.BEDROCK) || s.is(Blocks.BARRIER) || s.is(Blocks.END_PORTAL_FRAME)
            || s.getDestroySpeed(mc.level, pos) < 0;
    }

    /** True when an entity (other than items/xp, optionally crystals) blocks placing here. */
    public static boolean entityBlocks(BlockPos pos, boolean ignoreCrystals) {
        AABB box = new AABB(pos);
        for (Entity e : mc.level.getEntities(null, box)) {
            if (e instanceof ItemEntity || e instanceof ExperienceOrb) continue;
            if (ignoreCrystals && e instanceof EndCrystal) continue;
            if (!e.isSpectator() && e.isAlive()) return true;
        }
        return false;
    }

    public static boolean canPlace(BlockPos pos) {
        return mc.level.isInWorldBounds(pos) && isReplaceable(pos) && !entityBlocks(pos, false);
    }

    /**
     * Finds a neighbour to click against. Respects StrictDirection: the clicked face must be visible
     * from the eye (the eye is on the outer side of that face's plane).
     */
    public static Direction getPlaceSide(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        boolean strict = Prism.anticheat().strictDirection.get();
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.relative(d);
            BlockState ns = state(neighbor);
            if (ns.canBeReplaced() || ns.getCollisionShape(mc.level, neighbor).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(neighbor).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
            if (strict && !isFaceVisible(neighbor, face, eye)) continue;
            double dist = eye.distanceToSqr(hit);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        return best;
    }

    public static boolean isFaceVisible(BlockPos block, Direction face, Vec3 eye) {
        return switch (face) {
            case UP -> eye.y > block.getY() + 1;
            case DOWN -> eye.y < block.getY();
            case NORTH -> eye.z < block.getZ();
            case SOUTH -> eye.z > block.getZ() + 1;
            case WEST -> eye.x < block.getX();
            case EAST -> eye.x > block.getX() + 1;
        };
    }

    /**
     * The face of a block our eye ray enters through (Grim PositionBreakA: the dug face must be visible
     * from the eye). Null if no face is visible (we're inside it) - any face is fine then.
     */
    public static Direction breakFace(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        if (new AABB(pos).contains(eye)) return Direction.UP;
        BlockHitResult r = AABB.clip(java.util.List.of(new AABB(0, 0, 0, 1, 1, 1)), eye, Vec3.atCenterOf(pos), pos);
        if (r != null && isFaceVisible(pos, r.getDirection(), eye)) return r.getDirection();
        for (Direction d : Direction.values()) if (isFaceVisible(pos, d, eye)) return d;
        return null;
    }

    /**
     * Requests a silent rotation onto the block and returns true once the rotation the server has hits it
     * (Grim RotationBreak), and the block is in range to its closest point (FarBreak).
     */
    public static boolean readyToDig(BlockPos pos, int priority) {
        Vec3 aim = Vec3.atCenterOf(pos);
        Direction face = breakFace(pos);
        if (face != null) aim = aim.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.45));
        if (Prism.anticheat().strictRaycast.get() && mc.player.getEyePosition().distanceTo(aim) > Prism.rotations().serverReach() - 0.05) return false;
        float[] rot = RotationUtil.toward(aim);
        Prism.rotations().request(rot[0], rot[1], priority);
        double range = Prism.anticheat().placeRange.get();
        return RotationUtil.distanceToBox(mc.player.getEyePosition(), new AABB(pos)) <= range
            && Prism.rotations().isFacing(new AABB(pos), range);
    }

    public static Predicate<ItemStack> blockItem(net.minecraft.world.level.block.Block block) {
        return s -> s.getItem() instanceof BlockItem bi && bi.getBlock() == block;
    }

    /** Grim FarPlace: distance from the eye to the closest point of the block. */
    public static boolean inPlaceRange(BlockPos pos) {
        double range = Prism.anticheat().placeRange.get();
        return RotationUtil.distanceToBox(mc.player.getEyePosition(), new AABB(pos)) <= range;
    }

    public enum Result { PLACED, WAITING, FAILED }

    /**
     * Places a block from the given hotbar slot, honouring the AntiCheat profile.
     * In Silent rotate mode this may return WAITING for a few ticks while the server rotation turns.
     */
    public static Result place(BlockPos pos, int hotbarSlot, boolean swapBack, int rotationPriority) {
        if (!budgetLeft() || hotbarSlot < 0) return Result.FAILED;
        // torches, buttons, carpets... have no collision box: the server places them even with an entity in the way
        ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
        boolean solid = !(stack.getItem() instanceof BlockItem bi) || !bi.getBlock().defaultBlockState().getCollisionShape(mc.level, pos).isEmpty();
        if (solid ? !canPlace(pos) : !(mc.level.isInWorldBounds(pos) && isReplaceable(pos))) return Result.FAILED;
        AntiCheat ac = Prism.anticheat();

        Direction side = getPlaceSide(pos);
        BlockHitResult hit;
        if (side != null) {
            BlockPos neighbor = pos.relative(side);
            Direction face = side.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(neighbor).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
            if (!inPlaceRange(neighbor)) return Result.FAILED;
            hit = new BlockHitResult(hitVec, face, neighbor, false);
        } else if (ac.airPlace.get()) {
            hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        } else {
            return Result.FAILED;
        }

        // the look ray to the clicked point must fit in the server's reach (Grim RotationPlace casts exactly that far)
        if (ac.strictRaycast.get() && mc.player.getEyePosition().distanceTo(hit.getLocation()) > Prism.rotations().serverReach() - 0.05) {
            return Result.FAILED;
        }
        float[] rot = RotationUtil.toward(hit.getLocation());
        switch (ac.placeRotate.get()) {
            case "Packet" -> Prism.rotations().sendLook(rot[0], rot[1]);
            case "Silent" -> {
                // Grim RotationPlace ray-traces the clicked block with last tick's rotation: wait until
                // the rotation we already sent actually intersects it.
                Prism.rotations().request(rot[0], rot[1], rotationPriority);
                if (!Prism.rotations().isFacing(new AABB(hit.getBlockPos()), Prism.anticheat().placeRange.get())) {
                    return Result.WAITING;
                }
            }
            default -> {}
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        boolean switched = prev != hotbarSlot;
        if (switched) {
            if (!Prism.guard().canSwitchSlot()) return Result.WAITING; // would break PacketOrderE
            InvUtil.swap(hotbarSlot, false);
        }

        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        // no swing packet since 26.3: the server swings for the use packet itself

        if (switched && swapBack) InvUtil.restore(prev);
        placedThisTick++;
        return result.consumesAction() ? Result.PLACED : Result.FAILED;
    }

    /**
     * Uses the item in a hotbar slot on a face of a block (flint and steel on the ground, a bucket on a source...)
     * with the same anticheat handling as {@link #place}: range to the clicked point, silent rotation that must
     * already face the block, slot switch only when allowed, swap-back on the next tick. Counts as this tick's place.
     */
    public static Result click(BlockPos pos, Direction face, int hotbarSlot, boolean swapBack, int rotationPriority) {
        if (!budgetLeft() || hotbarSlot < 0) return Result.FAILED;
        AntiCheat ac = Prism.anticheat();
        Vec3 hitVec = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        if (!inPlaceRange(pos)) return Result.FAILED;
        if (ac.strictDirection.get() && !isFaceVisible(pos, face, mc.player.getEyePosition())) return Result.FAILED;
        if (ac.strictRaycast.get() && mc.player.getEyePosition().distanceTo(hitVec) > Prism.rotations().serverReach() - 0.05) return Result.FAILED;
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);
        float[] rot = RotationUtil.toward(hitVec);
        switch (ac.placeRotate.get()) {
            case "Packet" -> Prism.rotations().sendLook(rot[0], rot[1]);
            case "Silent" -> {
                Prism.rotations().request(rot[0], rot[1], rotationPriority);
                if (!Prism.rotations().isFacing(new AABB(pos), ac.placeRange.get())) return Result.WAITING;
            }
            default -> {}
        }
        int prev = mc.player.getInventory().getSelectedSlot();
        boolean switched = prev != hotbarSlot;
        if (switched) {
            if (!Prism.guard().canSwitchSlot()) return Result.WAITING;
            InvUtil.swap(hotbarSlot, false);
        }
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        // no swing packet since 26.3: the server swings for the use packet itself
        if (switched && swapBack) InvUtil.restore(prev);
        placedThisTick++;
        return result.consumesAction() ? Result.PLACED : Result.FAILED;
    }

    /**
     * What to place, in order, so {@code target} gets something to be placed against: just the target when it
     * already has a usable face, else a chain of up to {@code maxSupports} support blocks built out from the nearest
     * solid block (shortest first). Recompute each tick and place the first entry. Empty when no chain fits.
     * {@code allowed} filters support spots (keep an anvil's drop column clear, stay out of a hole...).
     */
    public static List<BlockPos> supportChain(BlockPos target, int maxSupports, Predicate<BlockPos> allowed) {
        // only a solid block in the way ends it: an entity there (us mid-jump, say) may move, the supports can go now
        if (!mc.level.isInWorldBounds(target) || !isReplaceable(target)) return List.of();
        if (getPlaceSide(target) != null) return List.of(target);
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        queue.add(target);
        depth.put(target, 0);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            int d = depth.get(cur);
            if (d >= maxSupports) continue;
            for (Direction dir : Direction.values()) {
                BlockPos n = cur.relative(dir);
                if (depth.containsKey(n) || !allowed.test(n) || !canPlace(n) || !inPlaceRange(n)) continue;
                parent.put(n, cur);
                depth.put(n, d + 1);
                if (getPlaceSide(n) != null) {
                    // n can be placed now: walk back to the target
                    List<BlockPos> chain = new ArrayList<>();
                    for (BlockPos p = n; p != null; p = parent.get(p)) chain.add(p);
                    return chain;
                }
                queue.add(n);
            }
        }
        return List.of();
    }

    public static int findBlock(net.minecraft.world.level.block.Block... blocks) {
        for (net.minecraft.world.level.block.Block b : blocks) {
            int slot = InvUtil.findHotbar(blockItem(b));
            if (slot != -1) return slot;
        }
        return -1;
    }

    public static BlockPos playerPos() {
        return BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
    }
}
