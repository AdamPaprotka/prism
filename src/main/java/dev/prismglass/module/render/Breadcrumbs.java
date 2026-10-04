package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.Deque;

public class Breadcrumbs extends Module {
    public final NumberSetting length = num("Length", 400, 20, 2000, 10, "Max points.");
    public final ColorSetting color = color("Color", 0xFF8AB4FF, "Trail colour.");
    private final Deque<Vec3> points = new ArrayDeque<>();

    public Breadcrumbs() { super("Breadcrumbs", "Draws a trail where you walked.", Category.RENDER); }

    @Override public void onEnable() { points.clear(); }

    @Override
    public void onTick() {
        Vec3 pos = mc.player.position().add(0, 0.1, 0);
        if (points.isEmpty() || points.peekLast().distanceToSqr(pos) > 0.01) points.addLast(pos);
        while (points.size() > length.getInt()) points.removeFirst();
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        Vec3 prev = null;
        int i = 0, n = points.size();
        for (Vec3 p : points) {
            if (prev != null) Render3D.line(prev, p, ColorUtil.fade(color.color(i * 20), 0.3f + 0.7f * i / n));
            prev = p;
            i++;
        }
    }
}
