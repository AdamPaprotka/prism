package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Eats when hungry (or a gapple when low), holding the real use key so it is a normal eat. */
public class AutoEat extends Module {
    public final NumberSetting hunger = num("Hunger", 14, 1, 19, 1, "Eat at or below this hunger.");
    public final NumberSetting gapHealth = num("GapHealth", 10, 0, 36, 0.5, "Eat a golden apple at or below this health (0 = off).");
    public final BoolSetting pauseAura = bool("PauseCombat", true, "Combat modules pause while eating (they check isUsingItem).");
    private int prevSlot = -1;
    private boolean eating;

    public AutoEat() { super("AutoEat", "Eats automatically.", Category.PLAYER); }

    @Override public void onDisable() { stop(); }

    @Override
    public void onTick() {
        var p = mc.player;
        int slot = -1;
        if (gapHealth.get() > 0 && DamageUtil.health(p) <= gapHealth.get()) {
            slot = InvUtil.findHotbar(Items.ENCHANTED_GOLDEN_APPLE);
            if (slot == -1) slot = InvUtil.findHotbar(Items.GOLDEN_APPLE);
        }
        if (slot == -1 && p.getFoodData().getFoodLevel() <= hunger.getInt()) slot = bestFood();
        if (slot == -1) { stop(); return; }

        if (!eating) {
            if (!Prism.guard().canSwitchSlot()) return;
            prevSlot = p.getInventory().getSelectedSlot();
            InvUtil.swap(slot, false);
            eating = true;
        }
        mc.options.keyUse.setDown(true);
    }

    private int bestFood() {
        int best = -1;
        float bestSat = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            FoodProperties food = s.get(DataComponents.FOOD);
            if (food == null || s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO)
                || s.is(Items.PUFFERFISH) || s.is(Items.CHORUS_FRUIT) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) continue;
            if (food.saturation() > bestSat) { bestSat = food.saturation(); best = i; }
        }
        return best;
    }

    private void stop() {
        if (!eating) return;
        eating = false;
        mc.options.keyUse.setDown(false);
        if (prevSlot != -1) InvUtil.restore(prevSlot);
        prevSlot = -1;
    }

    public boolean isEating() { return eating; }
}
