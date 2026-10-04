package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

public class Trigger extends Module {
    public final BoolSetting onlyPlayers = bool("OnlyPlayers", true, "Only players.");
    public Trigger() { super("Trigger", "Attacks whatever your crosshair is on (vanilla aim, safe).", Category.COMBAT); }

    @Override
    public void onTick() {
        if (!(mc.hitResult instanceof EntityHitResult hit)) return;
        Entity e = hit.getEntity();
        if (onlyPlayers.get() && !(e instanceof Player)) return;
        if (!(e instanceof LivingEntity le) || !le.isAlive() || Prism.friends().isFriend(e)) return;
        if (!Criticals.allowsAttack()) return;
        CombatUtil.attack(e, true);
    }
}
