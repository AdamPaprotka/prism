package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.util.InvUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks shields: when the entity you're about to hit is blocking and you're inside its shield's arc (the server
 * blocks a hit only within horizontal_blocking_angle, 90 deg by default, of where their head looks), the hit goes in
 * with the hotbar weapon that disables blocking longest (axes: 5 s), then the slot goes back next tick. A blocked
 * hit with a sword does nothing; this one turns their shield off.
 */
public class ShieldBypass extends Module {
    public final BoolSetting onlyPlayers = bool("OnlyPlayers", true, "Only for players (mobs rarely block).");

    private int restore = -1;

    public ShieldBypass() { super("ShieldBypass", "Hits blocking enemies with an axe to disable their shield.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (restore != -1) { InvUtil.restore(restore); restore = -1; }
    }

    @Override
    public void onAttack(Entity target) {
        if (!wantsAxe(target)) return;
        int slot = shieldBreaker();
        int prev = mc.player.getInventory().getSelectedSlot();
        if (slot == -1 || slot == prev || !Prism.guard().canSwitchSlot()) return;
        InvUtil.swap(slot, false); // before the attack packet in the same tick: PacketOrderE only flags after
        if (restore == -1) restore = prev;
    }

    /** True when this hit would be blocked by their shield (AutoWeapon asks too, so it picks an axe). */
    public boolean wantsAxe(Entity target) {
        if (!isEnabled() || !(target instanceof LivingEntity le) || !le.isBlocking()) return false;
        if (onlyPlayers.get() && !(target instanceof Player)) return false;
        ItemStack shield = le.getItemBlockingWith();
        BlocksAttacks blocks = shield == null ? null : shield.get(DataComponents.BLOCKS_ATTACKS);
        if (blocks == null) return false;
        float arc = 0;
        for (var r : blocks.damageReductions()) arc = Math.max(arc, r.horizontalBlockingAngle());
        if (arc == 0) return false; // blocks nothing from any direction
        // the server's test: angle between their head's look and the direction to us, flattened
        Vec3 view = le.calculateViewVector(0, le.getYHeadRot());
        Vec3 to = mc.player.position().subtract(le.position());
        to = new Vec3(to.x, 0, to.z).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.dot(view))))) <= arc;
    }

    /** Hotbar slot of the weapon that disables blocking the longest, or -1. */
    private int shieldBreaker() {
        int best = -1;
        float bestSeconds = 0;
        for (int i = 0; i < 9; i++) {
            var w = mc.player.getInventory().getItem(i).get(DataComponents.WEAPON);
            if (w != null && w.disableBlockingForSeconds() > bestSeconds) { bestSeconds = w.disableBlockingForSeconds(); best = i; }
        }
        return best;
    }
}
