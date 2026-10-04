package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Place blocks where you look, even with nothing under the crosshair.
 *
 * <p>Grim's AirLiquidPlace flags any click against air or liquid, and vanilla servers put the block at the
 * clicked air spot, so a click against nothing can't pass. "Grim" mode instead clicks a real neighbour of the
 * spot (any side: below, behind, beside) with a silent rotation onto that face, so it works whenever the spot
 * touches a block (green box) and refuses floating spots (red box). "Vanilla" clicks the air [Grim-unsafe].
 */
public class AirPlace extends Module {
    public final ModeSetting mode = mode("Mode", "Grim", "Grim = click a neighbouring block (spot must touch one). Vanilla = click the air [Grim-unsafe].", "Grim", "Vanilla");
    public final NumberSetting range = num("Range", 4, 1, 6, 0.5, "Distance in front of you.");
    private boolean wasPressed;
    private BlockPos pending;
    private int pendingTicks;

    public AirPlace() { super("AirPlace", "Place blocks in the air.", Category.PLAYER); }

    @Override
    public void onTick() {
        boolean pressed = mc.options.keyUse.isDown();
        if (pressed && !wasPressed && aimingAtAir()) {
            BlockPos bp = target();
            if (mode.is("Vanilla")) {
                if (BlockUtil.isReplaceable(bp)) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(bp), Direction.UP, bp, false));
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
            } else if (BlockUtil.canPlace(bp) && BlockUtil.getPlaceSide(bp) != null) {
                pending = bp;
                pendingTicks = 0;
            }
        }
        wasPressed = pressed;

        // Grim: the silent rotation may need a tick or two to reach the face before the click is valid
        if (pending != null) {
            int slot = mc.player.getInventory().getSelectedSlot();
            BlockUtil.Result r = mc.player.getMainHandItem().getItem() instanceof BlockItem
                ? BlockUtil.place(pending, slot, false, 60) : BlockUtil.Result.FAILED;
            if (r != BlockUtil.Result.WAITING || ++pendingTicks > 10) pending = null;
        }
    }

    private boolean aimingAtAir() {
        return mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.MISS && mc.player.getMainHandItem().getItem() instanceof BlockItem;
    }

    private BlockPos target() {
        return BlockPos.containing(mc.player.getEyePosition().add(mc.player.getLookAngle().scale(range.get())));
    }

    @Override
    public void onRender3D(com.mojang.blaze3d.vertex.PoseStack matrices, float delta) {
        if (!aimingAtAir()) return;
        BlockPos bp = target();
        boolean ok = mode.is("Vanilla") ? BlockUtil.isReplaceable(bp) : BlockUtil.canPlace(bp) && BlockUtil.getPlaceSide(bp) != null;
        if (ok) Render3D.box(new AABB(bp), 0x208AB4FF, 0xC08AB4FF, false);
        else Render3D.box(new AABB(bp), 0x18FF4A4A, 0x90FF4A4A, false);
    }

    /** Dev: place at this spot as if aimed at it. */
    public void debugPlace(BlockPos bp) { pending = bp; pendingTicks = 0; }
}
