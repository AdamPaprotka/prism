package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.Entity;

public class AutoWeapon extends Module {
    public final BoolSetting preferAxe = bool("PreferAxe", false, "Prefer axes (shield breaking).");
    public AutoWeapon() { super("AutoWeapon", "Switches to your best weapon when you attack.", Category.COMBAT); }

    @Override
    public void onAttack(Entity target) {
        int slot = CombatUtil.bestWeapon(preferAxe.get());
        // switching before the attack packet in the same tick is fine (PacketOrderE only flags after)
        if (slot != -1 && Prism.guard().canSwitchSlot()) InvUtil.swap(slot, false);
    }
}
