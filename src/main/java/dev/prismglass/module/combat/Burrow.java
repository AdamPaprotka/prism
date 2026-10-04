package dev.prismglass.module.combat;


import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Places a block inside your own feet using fake jump packets. [Grim-unsafe: fake positions] */
public class Burrow extends Module {
    public final ModeSetting block = mode("Block", "Obsidian", "Burrow block.", "Obsidian", "EnderChest");
    public final NumberSetting rubberband = num("Rubberband", 3, -10, 10, 0.5, "Lagback height after placing.");

    public Burrow() { super("Burrow", "Puts a block inside your feet.", Category.COMBAT); }

    @Override
    public void onTick() {
        setEnabled(false);
        BlockPos pos = mc.player.blockPosition();
        if (!BlockUtil.isReplaceable(pos) || !mc.player.onGround()) return;
        int slot = block.is("EnderChest") ? BlockUtil.findBlock(Blocks.ENDER_CHEST) : BlockUtil.findBlock(Blocks.OBSIDIAN);
        if (slot == -1) { ChatUtil.error("Burrow: no blocks in hotbar"); return; }
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        boolean hc = mc.player.horizontalCollision;
        for (double o : new double[]{0.42, 0.75, 1.01, 1.16}) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + o, z, false, hc));
        }
        int prev = mc.player.getInventory().getSelectedSlot();
        InvUtil.swap(slot, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, 0.5, 0), Direction.UP, pos.below(), false));
        mc.player.swing(InteractionHand.MAIN_HAND);
        InvUtil.restore(prev);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + rubberband.get(), z, false, hc));
    }
}
