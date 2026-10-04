package dev.prismglass.module.combat;

import net.minecraft.world.level.block.BedBlock;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bed bombing in the nether/end. A bed's head points the way you face, so we face the head direction
 * (silently) before placing, then click the bed to detonate it next tick.
 */
public class BedAura extends Module {
    public final NumberSetting targetRange = num("TargetRange", 8, 3, 12, 0.5, "Enemy range.");
    public final NumberSetting minDamage = num("MinDamage", 8, 0, 36, 0.5, "Minimum damage to the target.");
    public final NumberSetting maxSelf = num("MaxSelfDamage", 6, 0, 36, 0.5, "Maximum damage to yourself.");
    private BlockPos placedHead;

    public BedAura() { super("BedAura", "Bombs enemies with beds (nether/end).", Category.COMBAT); }

    @Override
    public void onTick() {
        if (mc.level.dimension() == Level.OVERWORLD) return; // beds don't explode in the overworld
        Player t = EntityUtil.closestEnemy(targetRange.get());
        if (t == null) return;
        Hud.setTarget(t);

        if (placedHead != null) {
            if (BlockUtil.state(placedHead).getBlock() instanceof net.minecraft.world.level.block.BedBlock && Prism.guard().canPlace()) {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(placedHead), Direction.UP, placedHead, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            placedHead = null;
            return;
        }

        int bed = InvUtil.findHotbar(s -> s.getItem() instanceof BedItem);
        if (bed == -1) return;
        BlockPos tp = t.blockPosition().above();
        for (Direction d : BlockUtil.HORIZONTALS) {
            BlockPos head = tp; // head inside the target's upper body block
            BlockPos foot = tp.relative(d.getOpposite());
            if (!BlockUtil.isReplaceable(head) || !BlockUtil.canPlace(foot) || !BlockUtil.inPlaceRange(foot)) continue;
            if (BlockUtil.isReplaceable(foot.below())) continue;
            Vec3 c = Vec3.atCenterOf(head);
            if (DamageUtil.bedDamage(t, c) < minDamage.get() || DamageUtil.bedDamage(mc.player, c) > maxSelf.get()) continue;
            float yaw = d.toYRot();
            Prism.rotations().request(yaw, 60f, 100);
            if (Math.abs(Mth.wrapDegrees(Prism.rotations().getServerYaw() - yaw)) > 40) return;
            int prev = mc.player.getInventory().getSelectedSlot();
            if (prev != bed && !Prism.guard().canSwitchSlot()) return;
            InvUtil.swap(bed, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(foot.below()).add(0, 0.5, 0), Direction.UP, foot.below(), false));
            mc.player.swing(InteractionHand.MAIN_HAND);
            InvUtil.restore(prev);
            placedHead = head;
            return;
        }
    }
}
