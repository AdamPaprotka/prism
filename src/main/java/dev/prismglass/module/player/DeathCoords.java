package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class DeathCoords extends Module {
    private boolean dead;
    private String last = "none";

    public DeathCoords() { super("DeathCoords", "Tells you where you died.", Category.PLAYER); }

    @Override
    public void onTick() {
        boolean nowDead = mc.player.isDeadOrDying() || mc.player.getHealth() <= 0;
        if (nowDead && !dead) {
            last = String.format("%d %d %d (%s)", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(),
                mc.level.dimension().identifier().getPath());
            ChatUtil.info("You died at \u00A7f" + last);
        }
        dead = nowDead;
    }

    @Override public String getInfo() { return last.equals("none") ? null : last; }
}
