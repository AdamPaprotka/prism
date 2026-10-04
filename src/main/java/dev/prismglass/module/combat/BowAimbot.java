package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.phys.Vec3;

/** Aims your bow at the nearest target with arrow-drop compensation (silent rotation). */
public class BowAimbot extends Module {
    public final NumberSetting range = num("Range", 40, 5, 100, 1, "Target range.");
    public BowAimbot() { super("BowAimbot", "Aims your bow for you.", Category.COMBAT); }

    @Override
    public void onTick() {
        if (!(mc.player.getMainHandItem().getItem() instanceof BowItem) || !mc.player.isUsingItem()) return;
        Player t = EntityUtil.closestEnemy(range.get());
        if (t == null) return;
        Hud.setTarget(t);
        float pull = Math.max(0.1f, BowItem.getPowerForTime(mc.player.getTicksUsingItem()));
        double v = pull * 3.0;
        Vec3 eye = mc.player.getEyePosition();
        // lead the target by flight time, then solve the drop
        double dist = eye.distanceTo(t.position());
        double time = dist / v;
        Vec3 aim = t.position().add(t.getDeltaMovement().scale(time)).add(0, t.getBbHeight() * 0.5, 0);
        double dx = aim.x - eye.x, dz = aim.z - eye.z, dy = aim.y - eye.y;
        double h = Math.sqrt(dx * dx + dz * dz);
        double g = 0.006;
        double root = v * v * v * v - g * (g * h * h + 2 * dy * v * v);
        if (root < 0) return;
        float pitch = (float) -Math.toDegrees(Math.atan((v * v - Math.sqrt(root)) / (g * h)));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        Prism.rotations().request(yaw, pitch, 60);
    }
}
