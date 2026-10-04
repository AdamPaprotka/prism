package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Mends armour by throwing XP at your feet (silent swap + silent look down). */
public class AutoXP extends Module {
    public final NumberSetting threshold = num("Durability", 80, 10, 100, 1, "Mend pieces below this %.");
    public final NumberSetting delay = num("Delay", 1, 1, 10, 1, "Ticks between throws.");
    public final BoolSetting onlyInHole = bool("OnlyInHole", false, "Only while you're in a hole.");
    private int ticks;

    public AutoXP() { super("AutoXP", "Auto-mend armour with XP bottles.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (++ticks < delay.getInt() || !needsMend()) return;
        if (onlyInHole.get() && !EntityUtil.isInHole(mc.player)) return;
        int slot = InvUtil.findHotbar(Items.EXPERIENCE_BOTTLE);
        if (slot == -1) return;
        Prism.rotations().request(mc.player.getYRot(), 90f, 40);
        if (Prism.rotations().getServerPitch() < 80f) return;
        int prev = mc.player.getInventory().getSelectedSlot();
        if (prev != slot && !Prism.guard().canSwitchSlot()) return;
        InvUtil.swap(slot, false);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        InvUtil.restore(prev);
        ticks = 0;
    }

    private boolean needsMend() {
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = mc.player.getItemBySlot(s);
            if (st.isDamageableItem() && 100f * (st.getMaxDamage() - st.getDamageValue()) / st.getMaxDamage() < threshold.get()) return true;
        }
        return false;
    }
}
