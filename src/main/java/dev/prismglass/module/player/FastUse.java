package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class FastUse extends Module {
    public final BoolSetting xp = bool("XP", true, "Experience bottles.");
    public final BoolSetting crystals = bool("Crystals", true, "End crystals.");
    public final BoolSetting blocks = bool("Blocks", false, "Blocks (FastPlace).");
    public final BoolSetting all = bool("All", false, "Every item.");

    public FastUse() { super("FastUse", "Removes the right-click delay.", Category.PLAYER); }

    @Override
    public void onTick() {
        ItemStack s = mc.player.getMainHandItem();
        if (all.get() || (xp.get() && s.is(Items.EXPERIENCE_BOTTLE)) || (crystals.get() && s.is(Items.END_CRYSTAL))
            || (blocks.get() && s.getItem() instanceof BlockItem)) {
            mc.rightClickDelay = 0;
        }
    }
}
