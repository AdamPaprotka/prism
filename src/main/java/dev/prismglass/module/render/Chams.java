package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Tinted entity models. Walls draws an extra pass of the model in your colour that ignores depth, so they show
 * through blocks; Tint recolours the normal model; Both does both. Built on vanilla's translucent entity shader
 * (ChamsMixin re-submits the model with util.ChamsRender's render type), so it's the real animated model, not a box.
 */
public class Chams extends Module {
    public final ModeSetting mode = mode("Mode", "Walls", "Walls = coloured model through blocks. Tint = recolour the normal model. Both.", "Walls", "Tint", "Both");
    public final BoolSetting players = bool("Players", true, "Players.");
    public final BoolSetting hostiles = bool("Hostiles", false, "Monsters.");
    public final BoolSetting passives = bool("Passives", false, "Animals.");
    public final BoolSetting self = bool("Self", false, "You (third person).");
    public final ColorSetting color = color("Color", 0x90FF4D8A, "Enemy colour (alpha = see-through amount).");
    public final ColorSetting friend = color("Friend", 0x904DFF8A, "Friend colour.");
    public final ColorSetting mob = color("Mob", 0x80FFB84D, "Mob colour.");

    public Chams() { super("Chams", "Coloured entity models, visible through walls.", Category.RENDER); }

    /** ARGB for this entity, or 0 for none. Called while its render state is extracted. */
    public int colorFor(LivingEntity e) {
        if (e == mc.player) return self.get() ? color.color() : 0;
        if (e instanceof Player p) {
            if (!players.get() || p.isSpectator()) return 0;
            return Prism.friends().isFriend(p) ? friend.color() : color.color();
        }
        if (e instanceof Enemy) return hostiles.get() ? mob.color() : 0;
        if (e instanceof Animal) return passives.get() ? mob.color() : 0;
        return 0;
    }

    public boolean walls() { return !mode.is("Tint"); }
    public boolean tint() { return !mode.is("Walls"); }

    /** Tint mode: the normal model's colour multiplier (opaque, so the model stays solid). */
    public int tintOf(int argb) { return ColorUtil.withAlpha(argb, 0xFF); }
}
