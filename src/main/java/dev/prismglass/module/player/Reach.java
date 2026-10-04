package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.HitboxUtil;

/**
 * Longer reach.
 * <ul>
 *   <li>Grim: 3.0 measured to the server-accepted target box (drawn + newest server position). On moving
 *       targets that's real extra reach Grim allows by design; on still targets it's vanilla.</li>
 *   <li>NCP: follows NoCheatPlus' dynamic reach budget (up to 4.02, shrinking toward 3.22 when spent),
 *       so long hits only happen when NCP would still accept them.</li>
 *   <li>Vanilla: flat bonus on the interaction range. [Grim-unsafe]</li>
 * </ul>
 */
public class Reach extends Module {
    public final ModeSetting mode = mode("Mode", "Grim", "Grim/NCP stay inside what the anticheat accepts; Vanilla is a flat bonus.", "Grim", "NCP", "Vanilla");
    public final NumberSetting entity = num("Entity", 0.5, 0, 3, 0.05, "Vanilla mode: extra entity reach. [Grim-unsafe]");
    public final NumberSetting block = num("Block", 0.5, 0, 3, 0.05, "Vanilla mode: extra block reach. [Grim-unsafe]");

    public Reach() { super("Reach", "Longer reach.", Category.PLAYER); }

    /** Range the crosshair may target in Grim/NCP mode. */
    public double allowedRange() {
        if (mode.is("NCP")) return Math.max(3.0, Prism.reachBudget().ncpAllowed());
        return 3.0 + HitboxUtil.margin() * 0.9;
    }

    /** Client interaction range, used by the PlayerEntity mixin. */
    public double entity(double original) {
        // Grim/NCP: vanilla targeting stays vanilla; the extra reach comes only from HitboxUtil.extendCrosshair,
        // which tests the server-accepted box against the allowed range
        return mode.is("Vanilla") ? original + entity.get() : original;
    }

    public double block(double original) { return mode.is("Vanilla") ? original + block.get() : original; }

    @Override
    public String getInfo() {
        return mode.is("NCP") ? String.format("NCP %.2f", Prism.reachBudget().ncpAllowed()) : mode.get();
    }
}
