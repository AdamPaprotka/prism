package dev.prismglass.module.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import net.minecraft.util.Mth;

/**
 * Locks or snaps your real camera rotation (bridging, highways, AFK spots). Snap rounds yaw to the nearest 45 deg
 * each frame, so moving the mouse past halfway flips to the next one. This is your actual rotation, sent like any
 * mouse movement, so there is nothing for an anticheat to see. Eased over a few frames instead of teleporting.
 */
public class Rotation extends Module {
    public final ModeSetting yawMode = mode("Yaw", "Snap", "Off, Snap = nearest 45 deg, Lock = always YawAngle.", "Off", "Snap", "Lock");
    public final NumberSetting yawAngle = num("YawAngle", 0, -180, 180, 1, "Lock: the yaw to hold (0 = south, 90 = west, 180 = north, -90 = east).").visibleWhen(() -> yawMode.is("Lock"));
    public final ModeSetting pitchMode = mode("Pitch", "Off", "Off or Lock = always PitchAngle.", "Off", "Lock");
    public final NumberSetting pitchAngle = num("PitchAngle", 0, -90, 90, 1, "Lock: the pitch to hold (positive = down).").visibleWhen(() -> pitchMode.is("Lock"));
    public final NumberSetting smooth = num("Smooth", 0.25, 0, 1, 0.05, "Easing: 0 = instant, higher = softer.");

    private long lastNanos;

    public Rotation() { super("Rotation", "Locks or snaps your yaw/pitch (real rotation, safe).", Category.PLAYER); }

    @Override public void onEnable() { lastNanos = 0; }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.05f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        // frame-rate independent ease: smooth 0.25 settles in about 0.15 s
        float k = smooth.get() <= 0 ? 1 : 1 - (float) Math.exp(-dt / (0.12f * smooth.getFloat()));
        apply(k);
    }

    private void apply(float k) {
        var p = mc.player;
        if (p == null) return;
        if (!yawMode.is("Off")) {
            float yaw = p.getYRot();
            float target = yawMode.is("Lock") ? yawAngle.getFloat() : Math.round(yaw / 45f) * 45f;
            float next = yaw + Mth.wrapDegrees(target - yaw) * k;
            p.setYRot(next);
            p.yRotO += next - yaw; // keep the frame interpolation from swinging back
        }
        if (pitchMode.is("Lock")) {
            float pitch = p.getXRot();
            float next = pitch + (pitchAngle.getFloat() - pitch) * k;
            p.setXRot(next);
            p.xRotO += next - pitch;
        }
    }

    @Override
    public String getInfo() {
        return yawMode.is("Off") ? null : yawMode.is("Lock") ? String.valueOf(yawAngle.getInt()) : "45";
    }
}
