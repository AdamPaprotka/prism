package dev.prismglass.module.player;

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
 * The fall that counts is the server's: it adds up every block you go down in your position packets until one says
 * you're on the ground, and a packet saying so applies what it has counted (damage above 3). The client's own
 * fallDistance can differ (flying resets it every tick), so this keeps the server's count from the packets we send
 * and acts on the bigger of the two. Packet mode claims the ground at 2 blocks, before any damage. While Flight flies
 * (Vanilla / Creative) the packet reset is used whatever the mode: that flight is already visible to anticheats,
 * and its descents and AntiKick dips would otherwise land as one big fall.
 */
public class NoFall extends Module {
    public final ModeSetting mode = mode("Mode", "Bucket", "Bucket is legit; Packet works on weak ACs.", "Bucket", "Packet");
    public final NumberSetting minFall = num("MinFall", 3, 2, 20, 0.5, "Only act after falling this far.");
    private BlockPos placedWater;
    /** The fall distance the server is counting, rebuilt from our own movement packets. */
    private double serverFall, lastSentY = Double.NaN;

    public NoFall() { super("NoFall", "Prevents fall damage.", Category.PLAYER); }

    @Override public void onEnable() { serverFall = 0; lastSentY = Double.NaN; }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (!(e.packet instanceof ServerboundMovePlayerPacket p)) return;
        var pl = mc.player;
        if (p.hasPosition()) {
            double y = p.getY(pl.getY());
            if (!Double.isNaN(lastSentY) && y < lastSentY && !pl.isInWater()) serverFall += lastSentY - y;
            lastSentY = y;
        }
        if (pl.isInWater() || pl.onClimbable() || pl.isFallFlying()) serverFall = 0; // the server resets these too
        boolean packet = mode.is("Packet") || flying();
        // a "standing" packet applies the counted fall: send it before the count reaches 3 (no damage below that)
        if (packet && fall() >= 2 && !pl.isFallFlying() && !p.isOnGround()) p.onGround = true;
        if (p.onGround) serverFall = 0;
    }

    /** The bigger of the server's count and the client's own. */
    private double fall() { return Math.max(serverFall, mc.player.fallDistance); }

    private boolean flying() {
        var f = Prism.modules().get(dev.prismglass.module.movement.Flight.class);
        return f != null && f.isEnabled() && (f.mode.is("Vanilla") || f.mode.is("Creative"));
    }

    @Override
    public void onTick() {
        if (!mode.is("Bucket") || flying()) return;
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
        if (fall() < minFall.get() || p.onGround() || p.isInWater()) return;
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
