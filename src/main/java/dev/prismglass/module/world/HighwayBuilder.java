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
 * Builds a highway in the compass direction you face (8 directions, diagonals included): digs a tunnel
 * Width x Height, paves the floor, fills liquids, adds guardrails and walks along as it goes.
 *
 * <p>One action per tick, in safety order: floor/liquids under the path, then the tunnel, then guardrails; it only
 * walks when the next stretch is dug and paved. Mining is one block at a time at vanilla speed with the rotation on
 * the block and placing goes through BlockUtil, so it follows the AntiCheat preset (Grim/NCP-safe).
 */
public class HighwayBuilder extends Module {
    public final NumberSetting width = num("Width", 3, 1, 7, 1, "Highway width (blocks).");
    public final NumberSetting height = num("Height", 3, 2, 5, 1, "Tunnel height (blocks).");
    public final ModeSetting floor = mode("Floor", "Obsidian", "What to pave missing floor with.", "Obsidian", "Netherrack", "AnyBlock", "None");
    public final BoolSetting rails = bool("Guardrails", true, "1-high walls on both sides.");
    public final BoolSetting fillLiquids = bool("FillLiquids", true, "Block off lava/water in the tunnel before digging.");
    public final BoolSetting walk = bool("Walk", true, "Walk along the highway as it's finished.");
    public final BoolSetting lockYaw = bool("LockYaw", true, "Keep your view along the highway while walking.");

    private static final String[] DIR_NAMES = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final int[][] DIRS = {{0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}}; // yaw 0, 45, 90...

    private BlockPos origin;
    private int dx, dz, dirIndex, startSlot = -1, placed, mined;
    private float yaw;
    private BlockPos digging;
    private Direction digFace;
    private boolean wantForward, warned;
    private float wantStrafe;

    public HighwayBuilder() { super("HighwayBuilder", "Digs, paves and walks a highway in the direction you face.", Category.WORLD); }

    @Override
    public void onEnable() {
        if (mc.player == null) return;
        dirIndex = Math.floorMod(Math.round(Mth.wrapDegrees(mc.player.getYRot()) / 45f), 8);
        dx = DIRS[dirIndex][0];
        dz = DIRS[dirIndex][1];
        yaw = dirIndex * 45f;
        origin = BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ());
        startSlot = mc.player.getInventory().getSelectedSlot();
        digging = null;
        placed = mined = 0;
        warned = false;
        ChatUtil.info("HighwayBuilder: building " + DIR_NAMES[dirIndex] + " from " + origin.toShortString());
    }

    @Override
    public void onDisable() {
        if (digging != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        digging = null;
        wantForward = false;
        if (startSlot != -1 && mc.player != null) Prism.guard().deferSlot(startSlot);
        startSlot = -1;
    }

    // ---- geometry: along/across the highway axis, through the centre of the origin block --------------

    private double len() { return Math.sqrt(dx * dx + dz * dz); }
    private double along(double x, double z) { return ((x - origin.getX() - 0.5) * dx + (z - origin.getZ() - 0.5) * dz) / len(); }
    private double across(double x, double z) { return ((x - origin.getX() - 0.5) * -dz + (z - origin.getZ() - 0.5) * dx) / len(); }
    private double along(BlockPos p) { return along(p.getX() + 0.5, p.getZ() + 0.5); }
    private double across(BlockPos p) { return across(p.getX() + 0.5, p.getZ() + 0.5); }
    private double halfWidth() { return (width.getInt() - 1) / 2.0 + 0.25; }
    private boolean inLane(BlockPos p) { return Math.abs(across(p)) <= halfWidth(); }
    private boolean inRail(BlockPos p) { double a = Math.abs(across(p)); return a > halfWidth() && a <= halfWidth() + 1; }

    @Override
    public void onTick() {
        var p = mc.player;
        wantForward = false;
        wantStrafe = 0;
        if (origin == null) { onEnable(); return; }
        double pAlong = along(p.getX(), p.getZ());
        int y0 = origin.getY();

        // 1) floor + liquids under the path (safety first), 2) the tunnel, 3) guardrails
        BlockPos place = null, dig = null, rail = null;
        double bestPlace = Double.MAX_VALUE, bestDig = Double.MAX_VALUE, bestRail = Double.MAX_VALUE;
        int r = 6;
        BlockPos c = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.getX() - r, y0 - 1, c.getZ() - r, c.getX() + r, y0 + height.getInt() - 1, c.getZ() + r)) {
            double a = along(pos);
            if (a < pAlong - 2 || a > pAlong + 5) continue;
            int h = pos.getY() - y0;
            BlockState st = mc.level.getBlockState(pos);
            boolean lane = inLane(pos), railCol = rails.get() && inRail(pos);
            if (h == -1 && (lane || railCol)) {
                if (!floor.is("None") && st.canBeReplaced() && a < bestPlace && reachable(pos)) { bestPlace = a; place = pos.immutable(); }
            } else if (lane && h >= 0) {
                boolean liquid = !st.getFluidState().isEmpty();
                if (liquid && fillLiquids.get() && st.canBeReplaced()) {
                    if (a < bestPlace && reachable(pos)) { bestPlace = a; place = pos.immutable(); }
                } else if (!st.canBeReplaced() && !BlockUtil.isUnbreakable(pos)) {
                    // nearest slice first, top down inside a slice (gravel falls into dug space, not on us)
                    double score = a * 10 - h;
                    if (score < bestDig && reachable(pos) && BlockUtil.breakFace(pos) != null) { bestDig = score; dig = pos.immutable(); }
                }
            } else if (railCol && h == 0 && st.canBeReplaced() && a < bestRail && reachable(pos)) {
                bestRail = a;
                rail = pos.immutable();
            }
        }

        if (place != null && placeBlock(place)) return;
        if (dig != null) { mine(dig); return; }
        if (digging != null) { mc.gameMode.stopDestroyBlock(); digging = null; }
        if (rail != null && placeBlock(rail)) return;

        // walk on once the next stretch is dug and paved
        if (walk.get() && clearAhead(pAlong)) {
            wantForward = true;
            double off = across(p.getX(), p.getZ());
            if (Math.abs(off) > 0.15) wantStrafe = (float) -Math.signum(off); // drift back to the centre line
            if (lockYaw.get()) p.setYRot(p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot())); // continuous (no 360 snaps)
        }
    }

    /** Next ~1.5 blocks of lane: open at feet and head height, solid floor (or floor None). */
    private boolean clearAhead(double pAlong) {
        var p = mc.player;
        int y0 = origin.getY();
        BlockPos c = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.getX() - 3, y0 - 1, c.getZ() - 3, c.getX() + 3, y0 + 1, c.getZ() + 3)) {
            double a = along(pos);
            if (a <= pAlong || a > pAlong + 1.6 || !inLane(pos)) continue;
            int h = pos.getY() - y0;
            BlockState st = mc.level.getBlockState(pos);
            if (h == -1 ? !floor.is("None") && st.getCollisionShape(mc.level, pos).isEmpty() : !st.getCollisionShape(mc.level, pos).isEmpty()) return false;
            if (h >= 0 && !st.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /** Can the look ray reach this block (its near side) within the server's block reach? Farther = walk closer. */
    private boolean reachable(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Direction face = BlockUtil.breakFace(pos);
        Vec3 aim = Vec3.atCenterOf(pos);
        if (face != null) aim = aim.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.45));
        return eye.distanceTo(aim) <= Prism.rotations().serverReach() - 0.1 && BlockUtil.inPlaceRange(pos);
    }

    private boolean placeBlock(BlockPos pos) {
        int slot = InvUtil.findHotbar(this::isPaving);
        if (slot == -1) {
            if (!warned) { ChatUtil.error("HighwayBuilder: out of " + floor.get().toLowerCase() + " in your hotbar"); warned = true; }
            return false;
        }
        warned = false;
        if (BlockUtil.getPlaceSide(pos) == null) return false; // nothing to click against yet (dig/pave the neighbour first)
        BlockUtil.Result res = BlockUtil.place(pos, slot, false, 60);
        if (res == BlockUtil.Result.PLACED) placed++;
        return res != BlockUtil.Result.FAILED;
    }

    private boolean isPaving(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        return switch (floor.get()) {
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
        if (!BlockUtil.readyToDig(pos, 55)) return; // rotation must be on the block first (Grim RotationBreak)
        if (mc.gameMode.continueDestroyBlock(pos, digFace)) p.swing(InteractionHand.MAIN_HAND);
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

    /** MovementHooks: real key presses while walking the highway. */
    public boolean wantsForward() { return isEnabled() && wantForward; }
    public float wantsStrafe() { return isEnabled() ? wantStrafe : 0; }

    @Override
    public void onRender3D(com.mojang.blaze3d.vertex.PoseStack matrices, float delta) {
        if (origin == null || mc.player == null) return;
        // the next stretch's outline
        double pAlong = along(mc.player.getX(), mc.player.getZ());
        Vec3 dir = new Vec3(dx, 0, dz).normalize(), side = new Vec3(-dz, 0, dx).normalize();
        Vec3 base = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5).add(dir.scale(pAlong + 3));
        Vec3 a = base.add(side.scale(halfWidth())), b = base.add(side.scale(-halfWidth()));
        Render3D.line(a, b, 0xC08AB4FF);
        Render3D.line(a.add(0, height.get(), 0), b.add(0, height.get(), 0), 0x808AB4FF);
    }

    @Override
    public String getInfo() { return origin == null ? null : DIR_NAMES[dirIndex] + " " + mined + "m " + placed + "p"; }
}
