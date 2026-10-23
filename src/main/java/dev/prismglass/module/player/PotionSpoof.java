package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Client-side potion effects. Hides what Blindness, Darkness and Nausea do to your screen while the effects stay on
 * your player (Blindness also stops sprinting, and Grim knows you have it: removing it would let you sprint where
 * the server says you can't). NightVision adds a client-only Night Vision, which only changes the lightmap.
 * Effects that change movement or mining (Speed, Slowness, Levitation, Haste...) are never touched: the server
 * predicts those.
 */
public class PotionSpoof extends Module {
    public final BoolSetting noBlindness = bool("NoBlindness", true, "Hide the Blindness fog (you still can't sprint, like the server expects).");
    public final BoolSetting noDarkness = bool("NoDarkness", true, "Hide the Darkness fog and pulsing.");
    public final BoolSetting noNausea = bool("NoNausea", true, "Hide the Nausea screen warp.");
    public final BoolSetting nightVision = bool("NightVision", false, "Client-side Night Vision (only you see it; changes nothing on the server).");

    private MobEffectInstance ours;

    public PotionSpoof() { super("PotionSpoof", "Hides Blindness/Darkness/Nausea visuals, client-side Night Vision.", Category.PLAYER); }

    @Override public void onDisable() { removeOurs(); }

    @Override
    public void onTick() {
        var p = mc.player;
        if (!nightVision.get()) { removeOurs(); return; }
        // a real Night Vision (potion, beacon...) wins; ours comes back when it runs out
        MobEffectInstance cur = p.getEffect(MobEffects.NIGHT_VISION);
        if (cur == null) {
            ours = new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false);
            p.addEffect(ours);
        }
    }

    private void removeOurs() {
        if (ours != null && mc.player != null && mc.player.getEffect(MobEffects.NIGHT_VISION) == ours) mc.player.removeEffect(MobEffects.NIGHT_VISION);
        ours = null;
    }

    /** Render hooks: whether this effect's visuals are hidden for the local player. */
    public static boolean hides(Holder<MobEffect> effect) {
        PotionSpoof ps = Prism.modules().get(PotionSpoof.class);
        if (ps == null || !ps.isEnabled()) return false;
        if (effect == MobEffects.BLINDNESS) return ps.noBlindness.get();
        if (effect == MobEffects.DARKNESS) return ps.noDarkness.get();
        if (effect == MobEffects.NAUSEA) return ps.noNausea.get();
        return false;
    }
}
