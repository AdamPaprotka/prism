package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.client.gui.screens.DeathScreen;

public class AutoRespawn extends Module {
    public AutoRespawn() { super("AutoRespawn", "Respawns instantly.", Category.PLAYER); }

    @Override
    public void onTick() {
        if (mc.screen instanceof DeathScreen) {
            mc.player.respawn();
            mc.setScreen(null);
        }
    }
}
