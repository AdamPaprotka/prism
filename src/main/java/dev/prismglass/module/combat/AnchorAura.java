package dev.prismglass.module.combat;

import net.minecraft.client.multiplayer.chat.report.Report.Result;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Respawn-anchor bombing (overworld/end): place, charge with glowstone, detonate. One step per tick. */
public class AnchorAura extends Module {
    public final NumberSetting targetRange = num("TargetRange", 8, 3, 12, 0.5, "Enemy range.");
    public final NumberSetting minDamage = num("MinDamage", 8, 0, 36, 0.5, "Minimum damage to the target.");
    public final NumberSetting maxSelf = num("MaxSelfDamage", 6, 0, 36, 0.5, "Maximum damage to yourself.");
    private BlockPos anchor;

    public AnchorAura() { super("AnchorAura", "Bombs enemies with respawn anchors.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (mc.level.dimension() == Level.NETHER) return; // anchors don't explode in the nether
        Player t = EntityUtil.closestEnemy(targetRange.get());
        if (t == null) { anchor = null; return; }
        Hud.setTarget(t);
        int anchorSlot = BlockUtil.findBlock(Blocks.RESPAWN_ANCHOR);
        int glow = InvUtil.findHotbar(Items.GLOWSTONE);
        if (anchorSlot == -1 || glow == -1) return;

        if (anchor != null && BlockUtil.state(anchor).is(Blocks.RESPAWN_ANCHOR)) {
            int charges = BlockUtil.state(anchor).getValue(RespawnAnchorBlock.CHARGE);
            if (charges == 0) interact(anchor, glow);
            else interact(anchor, anchorSlot == glow ? (glow + 1) % 9 : anchorSlot); // any non-glowstone item detonates
            return;
        }
        anchor = null;
        BlockPos best = null;
        float bestDmg = 0;
        BlockPos tp = t.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(tp.offset(-2, -1, -2), tp.offset(2, 2, 2))) {
            if (!BlockUtil.canPlace(pos) || !BlockUtil.inPlaceRange(pos)) continue;
            Vec3 c = Vec3.atCenterOf(pos);
            float dmg = DamageUtil.anchorDamage(t, c);
            if (dmg < minDamage.get() || dmg <= bestDmg) continue;
            if (DamageUtil.anchorDamage(mc.player, c) > maxSelf.get()) continue;
            best = pos.immutable();
            bestDmg = dmg;
        }
        if (best != null && BlockUtil.place(best, anchorSlot, true, 100) == BlockUtil.Result.PLACED) anchor = best;
    }

    private void interact(BlockPos pos, int slot) {
        if (!Prism.guard().canPlace()) return;
        Vec3 hit = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        float[] rot = RotationUtil.toward(hit);
        Prism.rotations().request(rot[0], rot[1], 100);
        if (!Prism.rotations().isFacing(new AABB(pos), Prism.anticheat().placeRange.get())) return;
        int prev = mc.player.getInventory().getSelectedSlot();
        if (prev != slot && !Prism.guard().canSwitchSlot()) return;
        InvUtil.swap(slot, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
        InvUtil.restore(prev);
    }
}
