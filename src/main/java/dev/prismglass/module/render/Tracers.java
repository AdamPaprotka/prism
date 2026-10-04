package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class Tracers extends Module {
    public final ModeSetting colorMode = mode("Color", "Distance", "Colour by distance or fixed.", "Distance", "Fixed");
    public final ColorSetting color = color("FixedColor", 0xFFFF5C7A, "Fixed tracer colour.");
    public final BoolSetting friends = bool("Friends", true, "Draw to friends (green).");
    public final NumberSetting alpha = num("Alpha", 200, 20, 255, 1, "Line opacity.");

    public Tracers() { super("Tracers", "Lines to players.", Category.RENDER); }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            boolean friend = Prism.friends().isFriend(p);
            if (friend && !friends.get()) continue;
            int c;
            if (friend) c = 0xFF5CFFB0;
            else if (colorMode.is("Fixed")) c = color.color();
            else c = ColorUtil.lerp(0xFFFF4D6A, 0xFF5CFF9D, (float) Mth.clamp(mc.player.distanceTo(p) / 60.0, 0, 1));
            Vec3 to = p.getPosition(delta).add(0, p.getBbHeight() * 0.5, 0);
            Render3D.tracer(to, ColorUtil.withAlpha(c, alpha.getInt()));
        }
    }
}
