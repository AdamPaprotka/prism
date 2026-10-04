package dev.prismglass.module.world;

import net.minecraft.tags.ItemTags;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Places and mines ender chests for obsidian (needs a non-silk-touch pickaxe in the hotbar). */
public class EChestFarmer extends Module {
    public final NumberSetting target = num("TargetObsidian", 64, 8, 576, 8, "Stop once you have this much obsidian.");
    private BlockPos pos;

    public EChestFarmer() { super("EChestFarmer", "Farms obsidian from ender chests.", Category.WORLD); }

    @Override public void onDisable() { if (pos != null) mc.gameMode.stopDestroyBlock(); pos = null; }

    @Override
    public void onTick() {
        if (InvUtil.count(Items.OBSIDIAN) >= target.getInt()) { ChatUtil.good("EChestFarmer done"); toggle(); return; }
        if (pos == null) {
            Direction d = mc.player.getDirection();
            pos = mc.player.blockPosition().relative(d);
        }
        if (BlockUtil.state(pos).is(Blocks.ENDER_CHEST)) {
            int pick = InvUtil.findHotbar(s -> s.is(net.minecraft.tags.ItemTags.PICKAXES));
            if (pick == -1) { ChatUtil.error("EChestFarmer: no pickaxe"); toggle(); return; }
            if (mc.player.getInventory().getSelectedSlot() != pick && Prism.guard().canSwitchSlot()) InvUtil.swap(pick, false);
            Direction face = BlockUtil.breakFace(pos);
            if (face == null || !BlockUtil.readyToDig(pos, 30)) return;
            mc.gameMode.continueDestroyBlock(pos, face);
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else if (BlockUtil.isReplaceable(pos)) {
            int chest = BlockUtil.findBlock(Blocks.ENDER_CHEST);
            if (chest == -1) { ChatUtil.error("EChestFarmer: out of ender chests"); toggle(); return; }
            BlockUtil.place(pos, chest, false, 30);
        }
    }
}
