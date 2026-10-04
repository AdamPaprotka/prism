package dev.prismglass.module.player;

import net.minecraft.core.component.predicates.DataComponentPredicate.Type;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * No fall damage.
 * <ul>
 *   <li>Bucket: real water-bucket clutch (silent look down, place, pick back up). Legit inputs.</li>
 *   <li>Packet: claim to be on ground while falling. [Grim-unsafe: NoFall/GroundSpoof]</li>
 * </ul>
 */
public class NoFall extends Module {
    public final ModeSetting mode = mode("Mode", "Bucket", "Bucket is legit; Packet works on weak ACs.", "Bucket", "Packet");
    public final NumberSetting minFall = num("MinFall", 3, 2, 20, 0.5, "Only act after falling this far.");
    private BlockPos placedWater;

    public NoFall() { super("NoFall", "Prevents fall damage.", Category.PLAYER); }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (mode.is("Packet") && e.packet instanceof ServerboundMovePlayerPacket p && mc.player.fallDistance > minFall.get() && !mc.player.isFallFlying()) {
            p.onGround = true;
        }
    }

    @Override
    public void onTick() {
        if (!mode.is("Bucket")) return;
        var p = mc.player;
        if (placedWater != null && p.onGround()) {
            int bucket = InvUtil.findHotbar(Items.BUCKET);
            if (bucket != -1) {
                Prism.rotations().request(p.getYRot(), 90f, 70);
                if (Prism.rotations().getServerPitch() > 80f && Prism.guard().canSwitchSlot()) {
                    int prev = p.getInventory().getSelectedSlot();
                    InvUtil.swap(bucket, false);
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    InvUtil.restore(prev);
                    placedWater = null;
                }
            } else placedWater = null;
            return;
        }
        if (p.fallDistance < minFall.get() || p.onGround() || p.isInWater()) return;
        Vec3 from = p.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(0, -4.5, 0), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        int water = InvUtil.findHotbar(Items.WATER_BUCKET);
        if (water == -1) return;
        Prism.rotations().request(p.getYRot(), 90f, 70);
        if (Prism.rotations().getServerPitch() < 80f || !Prism.guard().canSwitchSlot()) return;
        int prev = p.getInventory().getSelectedSlot();
        InvUtil.swap(water, false);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        InvUtil.restore(prev);
        placedWater = hit.getBlockPos().above();
    }

    @Override public String getInfo() { return mode.get(); }
}
