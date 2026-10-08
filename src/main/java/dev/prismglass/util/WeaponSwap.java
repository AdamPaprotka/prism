package dev.prismglass.util;

import dev.prismglass.Prism;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Swap to another weapon for exactly one hit: selected right before the attack packet (the server reads the held
 * item when it handles the attack, before its tick notices the switch, so the hit keeps the full charge), and the
 * old slot comes back at the start of next tick (a switch after the attack in the same tick = Grim PacketOrderE).
 */
public final class WeaponSwap {
    private static final Minecraft mc = Minecraft.getInstance();

    private WeaponSwap() {}

    public static int enchantLevel(ItemStack s, ResourceKey<Enchantment> key) {
        for (var e : s.getEnchantments().entrySet()) if (e.getKey().is(key)) return e.getIntValue();
        return 0;
    }

    /** Best hotbar slot by score (higher is better, <= 0 = unusable), or -1. */
    public static int best(Predicate<ItemStack> usable, java.util.function.ToIntFunction<ItemStack> score) {
        int best = -1, bestScore = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!usable.test(s)) continue;
            int sc = score.applyAsInt(s);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    /** Select {@code slot} for the coming attack and queue the swap back. False if a switch isn't allowed now. */
    public static boolean forThisHit(int slot) {
        int prev = mc.player.getInventory().getSelectedSlot();
        if (slot < 0 || slot == prev) return slot == prev;
        if (!Prism.guard().canSwitchSlot()) return false;
        InvUtil.swap(slot, false);
        Prism.guard().deferSlot(prev);
        return true;
    }
}
