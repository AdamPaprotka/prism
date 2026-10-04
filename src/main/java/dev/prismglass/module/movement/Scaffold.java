package dev.prismglass.module.movement;

import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.client.multiplayer.chat.report.Report.Result;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Places blocks under you. Placement goes through BlockUtil, so rotation, strict faces and
 * one-place-per-tick all follow the AntiCheat profile (Grim: rotates silently and only places once
 * the sent rotation really hits the support block).
 */
public class Scaffold extends Module {
    public final BoolSetting tower = bool("Tower", true, "Go straight up while holding jump.");
    public final ModeSetting towerMode = mode("TowerMode", "Legit", "Legit = real jumps, block placed under you at the top of each (Grim-safe). Fast = boost up on every place [Grim-unsafe].", "Legit", "Fast");
    public final BoolSetting safe = bool("SafeWalk", true, "Don't walk off edges: really sneaks for the moment you're at an edge with no block yet (Grim-safe).");
    public final BoolSetting swapBack = bool("SwapBack", true, "Return to your slot once you stop scaffolding (not after every block: NCP Scaffold.ToolSwitch).");
    public final BoolSetting sprint = bool("Sprint", false, "Allow sprinting while bridging. [NCP-unsafe: Scaffold.Sprint]");
    public final NumberSetting extend = num("Extend", 0, 0, 3, 1, "Place ahead of you. >0 is [Grim-unsafe].");

    public Scaffold() { super("Scaffold", "Bridge automatically.", Category.MOVEMENT); }

    private boolean sneakWanted;
    /** Ticks since the last place while we hold the block slot (-1 = not holding); the slot we came from. */
    private int heldSince = -1, prevSlot;
    private Vec3 walkDir;
    /** Dev: last placement attempt. */
    public BlockUtil.Result lastResult;

    @Override
    public void onTick() {
        var p = mc.player;
        sneakWanted = false;
        // swap back here, before the movement packet (after it = Grim Post)
        if (heldSince >= 0 && ++heldSince > 10) returnSlot();
        int slot = InvUtil.findHotbar(Scaffold::usable);
        if (slot == -1) return;
        // Which way we're really going. Not from the keys: with a silent rotation the move fix has remapped them
        // to the server yaw. The velocity is real; it's remembered while the sneak holds us still at an edge.
        Vec3 vel = p.getDeltaMovement();
        if (vel.horizontalDistanceSqr() > 0.02 * 0.02) walkDir = new Vec3(vel.x, 0, vel.z).normalize();
        if (!EntityUtil.isMoving()) walkDir = null;
        // Grim simulates the edge back-off only for a real sneak (fake SafeWalk = Simulation flag + setback),
        // so hold sneak while the ground a step ahead is still missing
        if (safe.get() && p.onGround() && walkDir != null) {
            net.minecraft.world.phys.AABB ahead = p.getBoundingBox().move(walkDir.x * 0.35, -0.5, walkDir.z * 0.35);
            sneakWanted = mc.level.noCollision(p, ahead);
        }
        // Look straight back at the block we stand on the whole time we bridge, like a real backwards bridger:
        // NCP Scaffold.Angle wants the look within 90 degrees of the clicked block, Scaffold.Rotate flags a big
        // turn right after a place, and the silent rotation is already there when the edge comes (Grim).
        BlockPos support = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
        if (walkDir != null && !BlockUtil.canPlace(support)) {
            float backYaw = (float) Math.toDegrees(Math.atan2(walkDir.x, -walkDir.z));
            // never sit on the +-180 line: NCP Scaffold.Rotate compares raw yaws, so 179.9 -> -179.9 reads as a 360 turn
            if (Math.abs(backYaw) > 176f) backYaw = Math.copySign(176f, backYaw);
            Prism.rotations().request(backYaw, 80f, 50);
            if (!sprint.get() && p.isSprinting()) p.setSprinting(false);
        }
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
        if (extend.getInt() > 0 && EntityUtil.isMoving()) {
            Vec3 dir = RotationUtil.direction(p.getYRot(), 0).normalize();
            for (int i = extend.getInt(); i >= 1; i--) {
                BlockPos ahead = BlockPos.containing(p.getX() + dir.x * i, p.getY() - 0.5, p.getZ() + dir.z * i);
                if (BlockUtil.canPlace(ahead) && BlockUtil.getPlaceSide(ahead) != null) { below = ahead; break; }
            }
        }
        if (!BlockUtil.canPlace(below)) return;
        BlockPos target = below;
        if (BlockUtil.getPlaceSide(target) == null) {
            // no support: place a neighbouring block first so we can bridge onto it
            for (Direction d : BlockUtil.HORIZONTALS) {
                BlockPos side = below.relative(d);
                if (BlockUtil.canPlace(side) && BlockUtil.getPlaceSide(side) != null) { target = side; break; }
            }
        }
        if (heldSince < 0) prevSlot = p.getInventory().getSelectedSlot();
        BlockUtil.Result r = BlockUtil.place(target, slot, false, 50);
        if (r == BlockUtil.Result.PLACED) heldSince = 0;
        lastResult = r;
        if (r == BlockUtil.Result.PLACED && towering() && towerMode.is("Fast")) p.setDeltaMovement(0, 0.42, 0);
    }

    private boolean towering() {
        return tower.get() && mc.player.input.keyPresses.jump() && !EntityUtil.isMoving();
    }

    @Override
    public void onDisable() {
        // a key or GUI click turns us off outside the tick (after the movement packet = Grim Post), and right
        // after a place a switch is NCP ToolSwitch: hand the swap-back to the start of the next tick
        if (heldSince >= 0 && swapBack.get() && mc.player != null && prevSlot != mc.player.getInventory().getSelectedSlot()) {
            dev.prismglass.Prism.guard().deferSlot(prevSlot);
        }
        heldSince = -1;
    }

    /** Back to the slot we came from once scaffolding paused (called at tick start, before the movement packet). */
    private void returnSlot() {
        if (heldSince >= 0 && swapBack.get() && mc.player != null) InvUtil.restore(prevSlot);
        heldSince = -1;
    }

    @Override
    public void onPostTick() {
        // Legit tower: jump again the tick we land (vanilla resets the jump delay on release anyway)
        if (towering() && towerMode.is("Legit")) mc.player.noJumpDelay = 0;
    }

    private static boolean usable(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        return b.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO) && !b.defaultBlockState().hasBlockEntity()
            && !(b instanceof net.minecraft.world.level.block.FallingBlock);
    }

    /** Fake (non-sneak) safewalk is off: the real sneak in {@link #wantsSneak()} does it Grim-safely. */
    public boolean safeWalk() { return false; }

    public boolean wantsSneak() { return isEnabled() && sneakWanted; }
}
