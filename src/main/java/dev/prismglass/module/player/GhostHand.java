package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Right-click opens the first container your crosshair line passes through, even behind walls (a chest under the
 * floor, a shulker in a wall). Vanilla servers only check you're within reach of the block; Grim checks the block is
 * visible from your eyes, so it only works there when nothing solid is in between. [Grim-unsafe]
 */
public class GhostHand extends Module {
    public final NumberSetting range = num("Range", 4.5, 1, 6, 0.5, "How far along your crosshair to look.");
    public final BoolSetting enderChests = bool("EnderChests", true, "Also open ender chests.");

    private boolean wasDown;

    public GhostHand() { super("GhostHand", "Open containers through walls. [Grim-unsafe]", Category.PLAYER); }

    @Override
    public void onTick() {
        boolean down = mc.options.keyUse.isDown() && mc.gui.screen() == null;
        boolean pressed = down && !wasDown;
        wasDown = down;
        if (!pressed) return;
        // the crosshair is on a container already: vanilla opens it
        if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && container(b.getBlockPos())) return;
        BlockPos hit = find();
        if (hit == null) return;
        while (mc.options.keyUse.consumeClick()) {} // vanilla would place/use the held item too
        Vec3 eye = mc.player.getEyePosition();
        var face = net.minecraft.core.Direction.getApproximateNearest(eye.subtract(net.minecraft.world.phys.Vec3.atCenterOf(hit)));
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(hit).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5)), face, hit, false));
        dev.prismglass.util.SwingUtil.use(InteractionHand.MAIN_HAND);
    }

    /** First container block along the look line (0.1 block steps). */
    private BlockPos find() {
        Vec3 eye = mc.player.getEyePosition(), look = mc.player.getViewVector(1f);
        BlockPos last = null;
        for (double d = 0; d <= range.get(); d += 0.1) {
            BlockPos p = BlockPos.containing(eye.add(look.scale(d)));
            if (p.equals(last)) continue;
            last = p;
            if (container(p)) return p;
        }
        return null;
    }

    private boolean container(BlockPos p) {
        BlockEntity be = mc.level.getBlockEntity(p);
        return be instanceof RandomizableContainerBlockEntity || be instanceof BaseContainerBlockEntity || (enderChests.get() && be instanceof EnderChestBlockEntity);
    }
}
