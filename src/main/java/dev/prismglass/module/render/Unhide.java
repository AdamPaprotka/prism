package dev.prismglass.module.render;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Shows invisible entities (invisibility potions, invisible armour stands...).
 * Translucent mode reuses vanilla's own "invisible teammate" ghost path (the one spectators see), just with
 * your alpha instead of the fixed 15%. Render only.
 */
public class Unhide extends Module {
    public final BoolSetting translucent = bool("Translucent", true, "Draw them see-through instead of fully visible.");
    public final NumberSetting alpha = num("Alpha", 90, 15, 255, 5, "Opacity in translucent mode (vanilla ghost = 38).");
    public final BoolSetting players = bool("Players", true, "Invisible players.");
    public final BoolSetting mobs = bool("Mobs", true, "Invisible mobs and armour stands.");

    private static Unhide instance;

    public Unhide() {
        super("Unhide", "Shows invisible players and entities.", Category.RENDER);
        instance = this;
    }

    public static Unhide active() { return instance != null && instance.isEnabled() ? instance : null; }

    public boolean applies(LivingEntity e) {
        return e instanceof Player ? players.get() : mobs.get();
    }

    /** ARGB colour vanilla multiplies the translucent model by. */
    public int ghostColor() { return (alpha.getInt() << 24) | 0xFFFFFF; }
}
