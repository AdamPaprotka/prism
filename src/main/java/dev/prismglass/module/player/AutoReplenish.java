package dev.prismglass.module.player;


import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.item.ItemStack;

/** Refills hotbar stacks from your inventory when they run low. */
public class AutoReplenish extends Module {
    public final NumberSetting threshold = num("Threshold", 8, 1, 63, 1, "Refill when a stack drops to this size.");
    public final NumberSetting delay = num("Delay", 100, 0, 1000, 10, "Delay in ms between moves.");
    private final Timer timer = new Timer();

    public AutoReplenish() { super("AutoReplenish", "Keeps hotbar stacks full.", Category.PLAYER); }

    @Override
    public void onTick() {
        if (!timer.passed(delay.get()) || mc.screen != null) return;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty() || !s.isStackable() || s.getCount() > threshold.getInt() || s.getCount() >= s.getMaxStackSize()) continue;
            for (int j = 9; j < 36; j++) {
                ItemStack o = mc.player.getInventory().getItem(j);
                if (ItemStack.isSameItemSameComponents(s, o)) {
                    if (!InvUtil.canClick()) return;
                    InvUtil.move(InvUtil.toScreenSlot(j), InvUtil.toScreenSlot(i));
                    timer.reset();
                    return;
                }
            }
        }
    }
}
