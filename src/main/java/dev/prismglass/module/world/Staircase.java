package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a staircase in the direction you face (N/E/S/W): Up climbs one block per step into the air, Down goes
 * one block lower per step (digs into the ground, or builds down off a cliff/tower).
 *
 * <p>Step k sits at origin + k*forward +/- k up. Each step gets its block placed and 3 blocks of headroom dug
 * above it (enough to jump up onto it / walk down under the ceiling). A step with nothing to click against gets
 * a support first: under it going up (beside the previous step), behind it going down (under the previous step).
 * Supports and steps are placed from the edge of the step you're on (sneaking there first), since the faces they
 * click are hidden from the middle of the block. Down in open air has nothing to click at all: it needs AirPlace
 * on in the AntiCheat settings (server permitting); into the ground it just digs.
 * One action per tick through BlockUtil and the dig rotation, like HighwayBuilder, so it follows the AntiCheat preset.
 */
public class Staircase extends Module {
    public final ModeSetting direction = mode("Direction", "Up", "Up: one block higher per step. Down: one block lower per step.", "Up", "Down");
    public final NumberSetting length = num("Length", 0, 0, 512, 1, "Steps to build (0 = until you turn it off or run out of blocks).");
    public final ModeSetting material = mode("Blocks", "AnyBlock", "Blocks to build with.", "AnyBlock", "Cobblestone", "Obsidian", "Netherrack");
    public final BoolSetting dig = bool("Dig", true, "Mine whatever is in the way of the headroom.");
    public final BoolSetting walk = bool("Walk", true, "Walk (and jump) along the stairs as they're finished.");
    public final BoolSetting lockYaw = bool("LockYaw", true, "Keep your view along the stairs while walking.");

    private BlockPos origin;
    private Direction forward;
    private int dy, placed, mined, startSlot = -1;
    private BlockPos digging;
    private Direction digFace;
    private boolean wantForward, wantBack, wantJump, wantSneak, warned;

    public Staircase() { super("Staircase", "Builds a staircase up or down in the direction you face.", Category.WORLD); }

    @Override
    public void onEnable() {
        if (mc.player == null) return;
        forward = mc.player.getDirection();
        dy = direction.is("Up") ? 1 : -1;
        // step 0 = the block you stand on; up: step 1 is at feet level in front of you, down: one below step 0
        origin = BlockUtil.playerPos().below();
        startSlot = mc.player.getInventory().getSelectedSlot();
        digging = null;
        placed = mined = lastStep = 0;
        warned = false;
        ChatUtil.info("Staircase: going " + direction.get().toLowerCase() + " " + forward.getName() + " from " + origin.toShortString());
    }

    @Override
    public void onDisable() {
        if (digging != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        digging = null;
        wantForward = wantJump = false;
        if (startSlot != -1 && mc.player != null) Prism.guard().deferSlot(startSlot);
        startSlot = -1;
    }

    /** Step k (k = 0 is the block you started on). */
    private BlockPos step(int k) { return origin.relative(forward, k).above(k * dy); }

    /** Index of the step under the player (along the stairs). */
    private int lastStep;

    /**
     * Index of the step you stand on, from your feet height (each step is one block higher/lower): leaning over an
     * edge while sneaking would round to the next step if it went by horizontal position. Kept while in the air.
     */
    private int playerStep() {
        if (mc.player.onGround()) lastStep = Math.max(0, (Mth.floor(mc.player.getY() + 0.2) - origin.getY() - 1) * dy);
        return lastStep;
    }

    /** Distance walked along the stairs from the centre of step 0. */
    private double along() {
        Vec3 p = mc.player.position();
        return (p.x - origin.getX() - 0.5) * forward.getStepX() + (p.z - origin.getZ() - 0.5) * forward.getStepZ();
    }

    @Override
    public void onTick() {
        var p = mc.player;
        wantForward = wantBack = wantJump = wantSneak = false;
        if (origin == null) { onEnable(); return; }
        int cur = playerStep();
        int last = length.getInt() > 0 ? length.getInt() : Integer.MAX_VALUE;
        if (cur >= last) { ChatUtil.good("Staircase: done, " + last + " steps"); toggle(); return; }

        // work on the next few steps, nearest first: headroom, then the step block (support first if needed)
        for (int k = cur; k <= Math.min(cur + 3, last); k++) {
            BlockPos s = step(k);
            if (k > 0 && dig.get()) {
                for (int h = 3; h >= 1; h--) { // top down: gravel/sand falls into dug space, not onto us
                    BlockPos head = s.above(h);
                    BlockState st = BlockUtil.state(head);
                    if (st.canBeReplaced() || BlockUtil.isUnbreakable(head)) continue;
                    if (!reachable(head) || BlockUtil.breakFace(head) == null) break;
                    mine(head);
                    return;
                }
            }
            if (digging != null) { mc.gameMode.stopDestroyBlock(); digging = null; }
            if (k > 0 && BlockUtil.isReplaceable(s)) {
                if (k > cur + 1 || !BlockUtil.inPlaceRange(s)) break; // only the next step, from the step you're on
                BlockPos target = s;
                if (!hasSolidNeighbour(s)) {
                    target = dy > 0 ? s.below() : s.relative(forward.getOpposite());
                    if (!BlockUtil.isReplaceable(target) || !hasSolidNeighbour(target)) {
                        if (!Prism.anticheat().airPlace.get()) {
                            if (!warned) ChatUtil.error("Staircase: nothing to build against here (open air) - turn on AirPlace in AntiCheat if the server allows it");
                            warned = true;
                            return;
                        }
                        target = s; // AirPlace clicks the air itself
                    }
                }
                // walk into position first: StrictDirection only offers faces visible from where you stand
                if (p.onGround() && !inPosition(target, cur)) return;
                placeBlock(target);
                return;
            }
        }

        // walk on once the next step is built and clear
        if (!walk.get()) return;
        BlockPos next = step(cur + 1);
        boolean ready = !BlockUtil.isReplaceable(next);
        for (int h = 1; h <= 3 && ready; h++) if (!BlockUtil.state(next.above(h)).getCollisionShape(mc.level, next.above(h)).isEmpty()) ready = false;
        if (!ready) return;
        // going down: let go near the edge so you drop onto the next step instead of overshooting it,
        // with a nudge if you stopped short of the drop
        double off = along() - cur;
        boolean slow = p.getDeltaMovement().horizontalDistance() < 0.03;
        wantForward = dy > 0 || off < 0.7 || (p.onGround() && slow);
        if (dy > 0 && p.onGround() && p.horizontalCollision) wantJump = true;
        if (lockYaw.get()) p.setYRot(p.getYRot() + Mth.wrapDegrees(forward.toYRot() - p.getYRot()));
    }

    private static boolean hasSolidNeighbour(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState st = BlockUtil.state(n);
            if (!st.canBeReplaced() && !st.getCollisionShape(mc.level, n).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Moves to where a block can be placed from: a block below your feet is clicked on faces only visible from the
     * edge of your step (sneak forward to it, sneaking keeps you from walking off); a block at feet level or higher
     * would overlap your hitbox from the edge, so that one is placed from the middle (back up if needed).
     */
    private boolean inPosition(BlockPos target, int cur) {
        double off = along() - cur;
        if (target.getY() < Mth.floor(mc.player.getY() + 0.2)) {
            if (off >= 0.6) return true; // eye past the block edge (sneaking lets you lean out to ~0.8)
            wantForward = wantSneak = true;
        } else {
            if (off <= 0.15) return true;
            wantBack = wantSneak = true;
        }
        return false;
    }

    private boolean reachable(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Direction face = BlockUtil.breakFace(pos);
        Vec3 aim = Vec3.atCenterOf(pos);
        if (face != null) aim = aim.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.45));
        return eye.distanceTo(aim) <= Prism.rotations().serverReach() - 0.1 && BlockUtil.inPlaceRange(pos);
    }

    private void placeBlock(BlockPos pos) {
        int slot = InvUtil.findHotbar(this::isBuildBlock);
        if (slot == -1) {
            if (!warned) { ChatUtil.error("Staircase: no " + material.get().toLowerCase() + " in your hotbar"); warned = true; }
            return;
        }
        warned = false;
        if (BlockUtil.place(pos, slot, false, 60) == BlockUtil.Result.PLACED) placed++;
    }

    private boolean isBuildBlock(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        return switch (material.get()) {
            case "Cobblestone" -> b == Blocks.COBBLESTONE;
            case "Obsidian" -> b == Blocks.OBSIDIAN;
            case "Netherrack" -> b == Blocks.NETHERRACK;
            default -> b.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO) && !b.defaultBlockState().hasBlockEntity()
                && !(b instanceof FallingBlock);
        };
    }

    private void mine(BlockPos pos) {
        var p = mc.player;
        if (!pos.equals(digging)) {
            if (digging != null) mc.gameMode.stopDestroyBlock();
            digging = pos;
            digFace = BlockUtil.breakFace(pos); // one face per block (Grim WrongBreak)
            int tool = bestTool(BlockUtil.state(pos));
            if (tool != -1 && tool != p.getInventory().getSelectedSlot() && Prism.guard().canSwitchSlot()) InvUtil.swap(tool, false);
        }
        if (!BlockUtil.readyToDig(pos, 55)) return;
        if (mc.gameMode.continueDestroyBlock(pos, digFace)) dev.prismglass.util.SwingUtil.punch();
        if (BlockUtil.isReplaceable(pos)) { mined++; digging = null; }
    }

    private int bestTool(BlockState state) {
        int best = -1;
        float bestSpeed = 1f;
        for (int i = 0; i < 9; i++) {
            float s = mc.player.getInventory().getItem(i).getDestroySpeed(state);
            if (s > bestSpeed) { bestSpeed = s; best = i; }
        }
        return best;
    }

    /** MovementHooks: real key presses while walking the stairs. */
    public boolean wantsForward() { return isEnabled() && wantForward; }
    public boolean wantsBack() { return isEnabled() && wantBack; }
    public boolean wantsJump() { return isEnabled() && wantJump; }
    public boolean wantsSneak() { return isEnabled() && wantSneak; }

    @Override
    public void onRender3D(com.mojang.blaze3d.vertex.PoseStack matrices, float delta) {
        if (origin == null || mc.player == null) return;
        int cur = playerStep();
        for (int k = cur + 1; k <= cur + 3; k++) {
            if (length.getInt() > 0 && k > length.getInt()) break;
            BlockPos s = step(k);
            if (BlockUtil.isReplaceable(s)) Render3D.box(new net.minecraft.world.phys.AABB(s), 0x408AB4FF, 0xC08AB4FF, true);
        }
    }

    @Override
    public String getInfo() { return forward == null ? null : direction.get() + " " + playerStep() + (length.getInt() > 0 ? "/" + length.getInt() : ""); }
}
