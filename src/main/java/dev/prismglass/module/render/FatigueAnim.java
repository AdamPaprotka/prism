package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * The mining fatigue look without the effect: your hand animations play slower, the way vanilla mining fatigue slows
 * the swing (swing length + 2 ticks per level, so 6 / (6 + 2 x Level) speed). Inspect and the item-switch lowering slow
 * by the same ratio. Visual only: attack cooldown, mining speed and everything the server sees are untouched.
 *
 * <p>Real mining fatigue slows Inspect too (on or off); with the module on, its Level adds to the real effect's.
 */
public class FatigueAnim extends Module {
    public final NumberSetting level = num("Level", 2, 1, 10, 1, "Fatigue level to look like (I-X): each level adds 2 ticks to the swing.");
    public final BoolSetting swing = bool("Swing", true, "Slow the swing (attacking, mining, using items).");
    public final BoolSetting inspect = bool("Inspect", true, "Slow the Inspect animation by the same ratio.");
    public final BoolSetting equip = bool("Equip", true, "Slow the hand lowering / raising when you switch items.");

    public FatigueAnim() { super("FatigueAnim", "Mining fatigue animations: your swing, Inspect and item switching play slower.", Category.RENDER); }

    private static FatigueAnim get() {
        FatigueAnim m = Prism.modules() == null ? null : Prism.modules().get(FatigueAnim.class);
        return m != null && m.isEnabled() ? m : null;
    }

    /** Swing length with the module's levels added (vanilla already added the real effect's). */
    public static int swingDuration(int vanilla) {
        FatigueAnim m = get();
        return m == null || !m.swing.get() ? vanilla : vanilla + 2 * m.level.getInt();
    }

    /** Animation speed factor for Inspect (includeReal: the real effect counts too). 1 = normal. */
    public static float animScale(boolean includeReal) {
        int levels = 0;
        var p = Minecraft.getInstance().player;
        if (includeReal && p != null) {
            MobEffectInstance e = p.getEffect(MobEffects.MINING_FATIGUE);
            if (e != null) levels += e.getAmplifier() + 1;
        }
        FatigueAnim m = get();
        if (m != null && m.inspect.get()) levels += m.level.getInt();
        return 6f / (6 + 2 * levels);
    }

    /** Factor for the item-switch hand movement per tick (module only: vanilla doesn't slow it for the effect). */
    public static float equipScale() {
        FatigueAnim m = get();
        return m == null || !m.equip.get() ? 1f : 6f / (6 + 2 * m.level.getInt());
    }

    @Override public String getInfo() { return roman(level.getInt()); }

    private static String roman(int n) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return r[Math.max(1, Math.min(10, n)) - 1];
    }
}
