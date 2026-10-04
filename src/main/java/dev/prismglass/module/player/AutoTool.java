package dev.prismglass.module.player;

import net.minecraft.core.component.predicates.DataComponentPredicate.Type;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class AutoTool extends Module {
    public final BoolSetting swapBack = bool("SwapBack", true, "Return to your slot when done.");
    public final BoolSetting antiBreak = bool("AntiBreak", true, "Never use a tool about to break.");
    private int prevSlot = -1;

    public AutoTool() { super("AutoTool", "Switches to the best tool while mining.", Category.PLAYER); }

    @Override
    public void onTick() {
        boolean mining = mc.options.keyAttack.isDown() && mc.hitResult instanceof BlockHitResult bhr
            && bhr.getType() == HitResult.Type.BLOCK;
        if (!mining) {
            if (prevSlot != -1 && swapBack.get()) { InvUtil.restore(prevSlot); prevSlot = -1; }
            return;
        }
        BlockState state = mc.level.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos());
        int best = -1;
        float bestSpeed = 1f;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (antiBreak.get() && s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() < 10) continue;
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) { bestSpeed = speed; best = i; }
        }
        if (best == -1 || best == mc.player.getInventory().getSelectedSlot() || !Prism.guard().canSwitchSlot()) return;
        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();
        InvUtil.swap(best, false);
    }
}
