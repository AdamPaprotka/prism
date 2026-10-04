package dev.prismglass.module.combat;

import net.minecraft.tags.ItemTags;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Smart offhand: your chosen item, falling back to a totem when health is low or a fall would hurt. */
public class Offhand extends Module {
    public final ModeSetting item = mode("Item", "Crystal", "Item to hold when safe.", "Totem", "Crystal", "Gapple");
    public final NumberSetting health = num("TotemHealth", 14, 0, 36, 0.5, "Switch to totem at or below this health.");
    public final BoolSetting swordGap = bool("SwordGap", true, "Hold a gapple while holding a sword and right clicking.");
    public final BoolSetting fallSafety = bool("FallSafety", true, "Totem when a fall would be dangerous.");
    public final NumberSetting delay = num("Delay", 0, 0, 500, 10, "Delay in ms between swaps.");
    private final Timer timer = new Timer();

    public Offhand() { super("Offhand", "Manages your offhand item.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (!timer.passed(delay.get())) return;
        Item want = wanted();
        if (mc.player.getOffhandItem().is(want)) return;
        int slot = InvUtil.findInventory(want);
        if (slot == -1 && want != Items.TOTEM_OF_UNDYING) slot = InvUtil.findInventory(Items.TOTEM_OF_UNDYING);
        if (slot == -1 || !InvUtil.canClick()) return;
        InvUtil.toOffhand(slot);
        timer.reset();
    }

    private Item wanted() {
        float hp = DamageUtil.health(mc.player);
        if (hp <= health.get()) return Items.TOTEM_OF_UNDYING;
        if (fallSafety.get() && mc.player.fallDistance > 3 && hp - (mc.player.fallDistance - 3) <= health.get()) return Items.TOTEM_OF_UNDYING;
        if (swordGap.get() && mc.player.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && mc.options.keyUse.isDown()) {
            return InvUtil.count(Items.ENCHANTED_GOLDEN_APPLE) > 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        }
        return switch (item.get()) {
            case "Crystal" -> Items.END_CRYSTAL;
            case "Gapple" -> InvUtil.count(Items.ENCHANTED_GOLDEN_APPLE) > 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
            default -> Items.TOTEM_OF_UNDYING;
        };
    }

    @Override public String getInfo() { return item.get(); }
}
