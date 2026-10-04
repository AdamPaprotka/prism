package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.phys.EntityHitResult;

/** Clicks for you while you hold attack. Uses the vanilla click path, so it's as legit as your CPS. */
public class AutoClicker extends Module {
    public final NumberSetting cps = num("CPS", 10, 1, 20, 1, "Clicks per second (randomised +-20%).");
    public final BoolSetting onlyEntities = bool("OnlyEntities", true, "Only click when aiming at an entity.");
    public final BoolSetting cooldown = bool("Cooldown", true, "Wait for full attack cooldown instead of CPS.");
    private long next;

    public AutoClicker() { super("AutoClicker", "Automatic clicking.", Category.PLAYER); }

    @Override
    public void onTick() {
        if (!mc.options.keyAttack.isDown() || mc.screen != null) return;
        if (onlyEntities.get() && !(mc.hitResult instanceof EntityHitResult)) return;
        if (cooldown.get()) {
            if (CombatUtil.cooldownReady()) mc.startAttack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < next) return;
        double interval = 1000.0 / cps.get();
        next = now + (long) (interval * (0.8 + Math.random() * 0.4));
        mc.startAttack();
    }
}
