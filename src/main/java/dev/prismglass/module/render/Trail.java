package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;

/** Cosmetic particle trail behind you (and others): client-side particles only, nobody else sees them. */
public class Trail extends Module {
    public final ModeSetting particle = mode("Particle", "Flame", "What the trail is made of.",
        "Flame", "SoulFire", "Heart", "Cherry", "EndRod", "Note", "Dust", "Rainbow");
    public final ColorSetting color = color("Color", 0xFFB197FC, "Dust colour.").visibleWhen(() -> particle.is("Dust"));
    public final NumberSetting amount = num("Amount", 2, 1, 10, 1, "Particles per tick.");
    public final BoolSetting self = bool("Self", true, "Your own trail.");
    public final BoolSetting others = bool("Others", false, "Other players' trails.");
    public final BoolSetting friendsOnly = bool("FriendsOnly", false, "Others: only friends.").visibleWhen(() -> others.get());
    public final BoolSetting firstPerson = bool("FirstPerson", false, "Show your own trail in first person too (it spawns at your feet).");

    private int hue;

    public Trail() { super("Trail", "Cosmetic particle trail behind you.", Category.RENDER); }

    @Override
    public void onTick() {
        hue = (hue + 7) % 360;
        for (Player p : mc.level.players()) {
            boolean me = p == mc.player;
            if (me ? !self.get() : !others.get() || (friendsOnly.get() && !Prism.friends().isFriend(p))) continue;
            if (me && !firstPerson.get() && mc.options.getCameraType().isFirstPerson()) continue;
            double dx = p.getX() - p.xo, dz = p.getZ() - p.zo, dy = p.getY() - p.yo;
            if (dx * dx + dy * dy + dz * dz < 1e-4) continue; // only while moving
            for (int i = 0; i < amount.getInt(); i++) {
                double f = (i + 1.0) / amount.getInt();
                double x = p.xo + dx * f + (Math.random() - 0.5) * 0.2;
                double y = p.yo + dy * f + 0.1 + Math.random() * 0.1;
                double z = p.zo + dz * f + (Math.random() - 0.5) * 0.2;
                mc.level.addParticle(options(), x, y, z, particle.is("Note") ? (hue / 360.0) : 0, particle.is("Heart") ? 0.05 : 0.01, 0);
            }
        }
    }

    private ParticleOptions options() {
        return switch (particle.get()) {
            case "SoulFire" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "Heart" -> ParticleTypes.HEART;
            case "Cherry" -> ParticleTypes.CHERRY_LEAVES;
            case "EndRod" -> ParticleTypes.END_ROD;
            case "Note" -> ParticleTypes.NOTE;
            case "Dust" -> new DustParticleOptions(color.color() & 0xFFFFFF, 1.0f);
            case "Rainbow" -> new DustParticleOptions(ColorUtil.rainbow(0, 255) & 0xFFFFFF, 1.0f);
            default -> ParticleTypes.FLAME;
        };
    }
}
