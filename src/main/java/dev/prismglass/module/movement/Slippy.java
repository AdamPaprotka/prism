package dev.prismglass.module.movement;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;

/**
 * Every block you walk on gets the friction you set (ice is 0.98, normal blocks 0.6, slime 0.8), so you glide like
 * on ice. SlippyMixin swaps the value inside your own ground movement only; anticheats that simulate movement expect
 * the real friction. [Grim-unsafe]
 */
public class Slippy extends Module {
    public final NumberSetting friction = num("Friction", 0.98, 0.6, 1.1, 0.01, "Ice is 0.98, blue ice 0.989; over 1 speeds you up on its own.");
    public final BoolSetting keepIce = bool("KeepIce", true, "Leave blocks that are already more slippery alone.");

    public Slippy() { super("Slippy", "Walk on every block like on ice. [Grim-unsafe]", Category.MOVEMENT); }

    /** SlippyMixin: the friction to use instead of vanilla's. */
    public float apply(float vanilla) {
        float f = friction.getFloat();
        return keepIce.get() && vanilla > f ? vanilla : f;
    }
}
