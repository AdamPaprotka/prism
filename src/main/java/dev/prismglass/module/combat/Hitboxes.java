package dev.prismglass.module.combat;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Bigger hitboxes.
 * <ul>
 *   <li>Grim: your crosshair targets the box Grim itself accepts (drawn position + newest server
 *       position, + its 0.03 idle margin). Moving targets get noticeably easier to hit and nothing flags,
 *       because Grim's Reach check uses that same union.</li>
 *   <li>Expand: flat growth on every side. Easy to see, but hits outside the real box flag Grim Hitbox/Reach.</li>
 * </ul>
 */
public class Hitboxes extends Module {
    public final ModeSetting mode = mode("Mode", "Grim", "Grim = server-accepted box (safe). Expand = flat growth [Grim-unsafe].", "Grim", "Expand");
    public final NumberSetting expand = num("Expand", 0.1, 0, 1, 0.01, "Expand mode: growth per side.");
    public final BoolSetting onlyPlayers = bool("OnlyPlayers", true, "Expand mode: only players.");

    public Hitboxes() { super("Hitboxes", "Easier to hit targets.", Category.COMBAT); }

    /** Targeting margin used by the Entity mixin (Expand mode only; Grim mode works on the crosshair). */
    public float expand(Entity e) {
        if (!mode.is("Expand")) return 0f;
        if (onlyPlayers.get() && !(e instanceof Player)) return 0f;
        return expand.getFloat();
    }

    @Override public String getInfo() { return mode.get(); }
}
