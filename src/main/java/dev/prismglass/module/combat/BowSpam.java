package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.item.BowItem;

public class BowSpam extends Module {
    public final NumberSetting ticks = num("Ticks", 3, 3, 20, 1, "Release after this many ticks of drawing.");
    public BowSpam() { super("BowSpam", "Fires your bow as fast as possible.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (mc.player.getMainHandItem().getItem() instanceof BowItem && mc.player.isUsingItem()
            && mc.player.getTicksUsingItem() >= ticks.getInt()) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
    }
}
