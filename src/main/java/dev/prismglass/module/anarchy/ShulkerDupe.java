package dev.prismglass.module.anarchy;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * The shulker break dupe: mine the shulker box while its screen is open, and in the same tick the
 * break goes out shift-click every item out of it. A server that drops the box with its contents but
 * still accepts clicks on the broken box's menu gives you both. Vanilla and current Paper drop clicks
 * on a menu that is no longer valid, so this only works on servers missing that check.
 * Open a placed shulker box with the module on; hold a pickaxe for a fast break.
 */
public class ShulkerDupe extends Module {
    public final BoolSetting once = bool("Once", true, "Turn off after one attempt.");
    public final BoolSetting close = bool("Close", true, "Close the screen after the attempt.");

    private BlockPos box;
    private int attempts;

    public ShulkerDupe() { super("ShulkerDupe", "Mines an open shulker box and takes its items in the same tick (unpatched servers).", Category.ANARCHY); }

    @Override public void onEnable() { box = null; }

    @Override public String getInfo() { return attempts > 0 ? String.valueOf(attempts) : null; }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (e.packet instanceof ServerboundUseItemOnPacket p
            && BlockUtil.state(p.hitResult().getBlockPos()).getBlock() instanceof ShulkerBoxBlock) {
            box = p.hitResult().getBlockPos();
        }
    }

    @Override
    public void onTick() {
        if (box == null || !(mc.player.containerMenu instanceof ShulkerBoxMenu menu)) return;
        if (!(BlockUtil.state(box).getBlock() instanceof ShulkerBoxBlock)) { box = null; return; }
        Direction face = BlockUtil.breakFace(box);
        mc.gameMode.continueDestroyBlock(box, face == null ? Direction.UP : face);
        dev.prismglass.util.SwingUtil.punch();
        if (!BlockUtil.state(box).isAir()) return;

        // the break packet just went out: take everything in the same tick
        for (int slot = 0; slot < 27; slot++) {
            if (menu.getSlot(slot).hasItem()) {
                mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, mc.player);
            }
        }
        attempts++;
        box = null;
        ChatUtil.info("ShulkerDupe: attempt sent");
        if (close.get()) mc.player.closeContainer();
        if (once.get()) toggle();
    }
}
