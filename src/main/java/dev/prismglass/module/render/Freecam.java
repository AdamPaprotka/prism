package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.client.player.ClientInput;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Detached camera. Your player stays still (no packets change); only the view moves. */
public class Freecam extends Module {
    public final NumberSetting speed = num("Speed", 1.0, 0.1, 5, 0.1, "Fly speed.");
    public final BoolSetting forceRender = bool("ForceRender", true, "Turn off chunk occlusion culling while flying, so nothing behind walls or behind your body goes missing.");
    private boolean savedCull = true, cullChanged;
    private Vec3 pos = Vec3.ZERO, prevPos = Vec3.ZERO;
    private float yaw, pitch;

    public Freecam() { super("Freecam", "Fly the camera around freely.", Category.RENDER); }

    @Override
    public void onEnable() {
        pos = prevPos = mc.gameRenderer.getMainCamera().position();
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        if (forceRender.get()) {
            // vanilla hides chunk sections it thinks the camera can't see, flooding out from the camera's section;
            // a camera inside blocks or far from the player gets that wrong and whole areas vanish
            savedCull = mc.smartCull;
            mc.smartCull = false;
            cullChanged = true;
            if (mc.levelRenderer != null) mc.levelRenderer.needsUpdate();
        }
    }

    @Override
    public void onDisable() {
        if (cullChanged) { mc.smartCull = savedCull; cullChanged = false; }
        if (mc.levelRenderer != null) mc.levelRenderer.needsUpdate();
    }

    /** Called once per tick with the raw keyboard input (before it is zeroed for the player). */
    public void captureInput(ClientInput input) {
        prevPos = pos;
        double s = speed.get() * (mc.options.keySprint.isDown() ? 2 : 1);
        float f = input.getMoveVector().y, st = input.getMoveVector().x;
        double rad = Math.toRadians(yaw);
        double mx = (st * Math.cos(rad) - f * Math.sin(rad)) * s;
        double mz = (f * Math.cos(rad) + st * Math.sin(rad)) * s;
        double my = (input.keyPresses.jump() ? s : 0) - (input.keyPresses.shift() ? s : 0);
        pos = pos.add(mx, my, mz);
    }

    public void rotate(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = Mth.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }

    public Vec3 position(float delta) { return prevPos.lerp(pos, delta); }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
}
