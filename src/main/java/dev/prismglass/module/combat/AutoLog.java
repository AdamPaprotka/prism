package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

public class AutoLog extends Module {
    public final NumberSetting health = num("Health", 6, 0, 20, 0.5, "Disconnect at or below this health.");
    public final BoolSetting noTotems = bool("NoTotems", false, "Disconnect when you run out of totems.");
    public final BoolSetting onPlayer = bool("PlayerNear", false, "Disconnect when a non-friend comes close.");
    public final NumberSetting playerRange = num("PlayerRange", 32, 4, 128, 1, "Range for PlayerNear.");

    public AutoLog() { super("AutoLog", "Disconnects you when in danger.", Category.COMBAT); }

    @Override
    public void onTick() {
        String reason = null;
        if (DamageUtil.health(mc.player) <= health.get()) reason = "low health";
        else if (noTotems.get() && InvUtil.count(Items.TOTEM_OF_UNDYING) == 0) reason = "no totems";
        else if (onPlayer.get() && EntityUtil.closestEnemy(playerRange.get()) != null) reason = "player nearby";
        if (reason == null) return;
        setEnabled(false);
        mc.getConnection().getConnection().disconnect(Component.literal("[Prism AutoLog] " + reason));
    }
}
