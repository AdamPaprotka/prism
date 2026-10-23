package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Repairs your Mending gear. XP mends a random damaged Mending item you're wearing or holding, so your armour fixes
 * itself while it's on; tools and weapons in the inventory don't. AutoMend puts a damaged Mending tool in your
 * offhand, swaps it for the next one when it's fixed, and (ThrowXP) throws XP bottles at your feet with a silent
 * look down while anything Mending is damaged. It leaves the offhand alone while AutoTotem or Offhand run, and
 * pauses with an enemy close.
 */
public class AutoMend extends Module {
    public final NumberSetting below = num("Below", 99, 10, 100, 1, "Mend items under this % durability.");
    public final BoolSetting tools = bool("Tools", true, "Cycle damaged Mending tools/weapons through your offhand.");
    public final BoolSetting throwXp = bool("ThrowXP", true, "Throw XP bottles while something is damaged.");
    public final NumberSetting delay = num("Delay", 2, 1, 10, 1, "Ticks between bottles.");
    public final BoolSetting pauseInCombat = bool("PauseInCombat", true, "Do nothing while an enemy is within 8 blocks.");

    private int ticks, restore = -1, clickCooldown;

    public AutoMend() { super("AutoMend", "Repairs Mending gear: cycles tools through the offhand, throws XP.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (restore != -1) { InvUtil.restore(restore); restore = -1; }
        if (clickCooldown > 0) clickCooldown--;
        if (mc.screen != null || (pauseInCombat.get() && EntityUtil.closestEnemy(8) != null)) return;

        if (tools.get() && !offhandTaken() && clickCooldown == 0) {
            ItemStack off = mc.player.getOffhandItem();
            if (!needs(off) && !off.is(Items.TOTEM_OF_UNDYING)) {
                int slot = mostDamaged();
                if (slot != -1 && InvUtil.canClick()) { InvUtil.toOffhand(slot); clickCooldown = 5; return; }
            }
        }

        if (!throwXp.get() || ++ticks < delay.getInt() || !anythingToMend()) return;
        int bottle = InvUtil.findHotbar(Items.EXPERIENCE_BOTTLE);
        if (bottle == -1) return;
        Prism.rotations().request(mc.player.getYRot(), 90f, 35);
        if (Prism.rotations().getServerPitch() < 80f) return; // the bottle has to land at our feet
        int prev = mc.player.getInventory().getSelectedSlot();
        if (prev != bottle) {
            if (!Prism.guard().canSwitchSlot()) return;
            InvUtil.swap(bottle, false);
            restore = prev;
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        ticks = 0;
    }

    /** AutoTotem / Offhand own the offhand: never fight them over it. */
    private boolean offhandTaken() {
        var totem = Prism.modules().get(AutoTotem.class);
        var off = Prism.modules().get(Offhand.class);
        return (totem != null && totem.isEnabled()) || (off != null && off.isEnabled());
    }

    private boolean needs(ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem() || !s.isDamaged()) return false;
        boolean mending = false;
        for (var e : s.getEnchantments().keySet()) if (e.is(Enchantments.MENDING)) { mending = true; break; }
        return mending && 100f * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage() < below.get();
    }

    private boolean anythingToMend() {
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            if (needs(mc.player.getItemBySlot(s))) return true;
        }
        return false;
    }

    /** Inventory slot (0-35) of the most worn Mending item that isn't armour being worn, or -1. */
    private int mostDamaged() {
        int best = -1;
        float worst = 101;
        var inv = mc.player.getInventory();
        int selected = inv.getSelectedSlot();
        for (int i = 0; i < 36; i++) {
            if (i == selected) continue; // the held one is already being mended
            ItemStack s = inv.getItem(i);
            if (!needs(s)) continue;
            float pct = 100f * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage();
            if (pct < worst) { worst = pct; best = i; }
        }
        return best;
    }
}
