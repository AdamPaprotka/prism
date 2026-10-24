package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.EntityUtil;
import dev.prismglass.util.InvUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;

/**
 * Names nearby unnamed mobs with a renamed name tag from the hotbar, so they never despawn (pets, villagers in a
 * trading hall, a mob farm's last zombie...). Tag switch is held for the click and put back the next tick.
 */
public class AutoNametag extends Module {
    public final NumberSetting range = num("Range", 4, 1, 6, 0.5, "Reach to the mob's hitbox.");
    public final BoolSetting animals = bool("Animals", true, "Name animals.");
    public final BoolSetting hostiles = bool("Hostiles", false, "Name monsters.");
    public final BoolSetting others = bool("Others", true, "Name everything else (villagers, golems, fish, bats...).");
    public final BoolSetting babies = bool("Babies", false, "Name baby mobs too.");

    private int restore = -1;

    public AutoNametag() { super("AutoNametag", "Uses your renamed name tags on unnamed mobs so they never despawn.", Category.WORLD); }

    @Override public void onDisable() { if (restore != -1) InvUtil.restore(restore); restore = -1; }

    @Override
    public void onTick() {
        if (restore != -1) { InvUtil.restore(restore); restore = -1; return; }
        if (mc.gui.screen() != null) return;
        int slot = InvUtil.findHotbar(s -> s.is(Items.NAME_TAG) && s.has(DataComponents.CUSTOM_NAME));
        if (slot == -1) return;
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Mob m) || !wanted(m)) continue;
            double d = mc.player.distanceToSqr(m);
            if (d < bestDist) { bestDist = d; best = m; }
        }
        if (best == null) return;
        int prev = mc.player.getInventory().getSelectedSlot();
        if (prev != slot) {
            // only switch once the rotation already faces it, so the switch and the click land in the same tick
            if (!Prism.rotations().isFacing(best.getBoundingBox(), range.get())) {
                Prism.rotations().request(best.getBoundingBox().getCenter(), 25);
                return;
            }
            if (!Prism.guard().canSwitchSlot()) return;
            InvUtil.swap(slot, false);
        }
        EntityUtil.interact(best, 25, range.get());
        if (prev != slot) restore = prev;
    }

    private boolean wanted(Mob m) {
        if (!m.isAlive() || m.hasCustomName() || (m.isBaby() && !babies.get())) return false;
        if (m instanceof Animal) return animals.get();
        if (m instanceof Enemy) return hostiles.get();
        return others.get();
    }
}
