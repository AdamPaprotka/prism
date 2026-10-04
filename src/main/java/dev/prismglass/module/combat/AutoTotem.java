package dev.prismglass.module.combat;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.item.Items;

/** Keeps a totem in your offhand. Skips when Offhand is enabled (that module manages the slot). */
public class AutoTotem extends Module {
    public final NumberSetting delay = num("Delay", 0, 0, 500, 10, "Delay in ms between swaps.");
    private final Timer timer = new Timer();

    public AutoTotem() { super("AutoTotem", "Keeps a totem in your offhand.", Category.COMBAT); }

    @Override
    public void onTick() {
        Offhand off = Prism.modules().get(Offhand.class);
        if (off != null && off.isEnabled()) return;
        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
        if (!timer.passed(delay.get())) return;
        int slot = InvUtil.findInventory(Items.TOTEM_OF_UNDYING);
        if (slot == -1 || !InvUtil.canClick()) return;
        InvUtil.toOffhand(slot);
        timer.reset();
    }

    @Override public String getInfo() { return String.valueOf(InvUtil.count(Items.TOTEM_OF_UNDYING)); }
}
