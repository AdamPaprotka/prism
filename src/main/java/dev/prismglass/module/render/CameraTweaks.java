package dev.prismglass.module.render;

import com.mojang.blaze3d.platform.InputConstants;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.KeyUtil;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;

/**
 * Camera options: third-person distance/clipping, stretched resolution and freelook.
 * <ul>
 *   <li><b>Stretch</b>: the world (and your hand) is projected for a narrower screen (4:3...) and fills yours, so
 *       everything looks wider, like stretched res in CS. Only the projection changes: HUD and menus stay normal,
 *       and everything Prism draws in the world uses the same projection, so it stays lined up.</li>
 *   <li><b>Freelook</b>: while the key is held, the mouse turns only the camera. You keep walking and facing where you
 *       were, so the server never sees you turn.</li>
 * </ul>
 */
public class CameraTweaks extends Module {
    public final BoolSetting clip = bool("Clip", true, "Third-person camera goes through blocks.");
    public final NumberSetting distance = num("Distance", 4, 1, 20, 0.5, "Third-person distance.");
    public final BoolSetting stretch = bool("Stretch", false, "Stretched resolution: the world is drawn for a narrower screen and stretched to fill yours, so everything looks wider (like CS stretched res).");
    public final ModeSetting ratio = mode("Ratio", "4:3", "Screen shape the world is drawn for before it's stretched.", "4:3", "5:4", "16:10", "1:1", "Custom")
        .visibleWhen(stretch::get);
    public final NumberSetting customRatio = num("CustomRatio", 1.33, 0.5, 3, 0.01, "Width / height for Custom (1.33 = 4:3, 1.25 = 5:4).")
        .visibleWhen(() -> stretch.get() && ratio.is("Custom"));
    public final KeySetting freelook = key("Freelook", InputConstants.KEY_LALT, "Look around without turning: you keep walking where you were facing and the server sees no turn.");
    public final ModeSetting freelookMode = mode("FreelookMode", "Hold", "Hold the key, or press it once to switch freelook on and again for off.", "Hold", "Toggle");
    public final BoolSetting freelookThirdPerson = bool("FreelookThirdPerson", true, "Go to third person while freelooking (back to how it was after).");

    private boolean freelooking, keyWasDown;
    private float lookYaw, lookPitch;
    private CameraType before;

    public CameraTweaks() { super("CameraTweaks", "Third-person camera, stretched resolution and freelook.", Category.RENDER); }

    public float distance(float vanillaClipped) {
        return clip.get() ? distance.getFloat() : Math.min(vanillaClipped, distance.getFloat());
    }

    // ---- stretch -------------------------------------------------------------------------------------------------

    /** Width/height the world is drawn at (0 = not stretching). */
    private double stretchRatio() {
        if (!isEnabled() || !stretch.get()) return 0;
        return switch (ratio.get()) {
            case "5:4" -> 5.0 / 4;
            case "16:10" -> 16.0 / 10;
            case "1:1" -> 1;
            case "Custom" -> customRatio.get();
            default -> 4.0 / 3;
        };
    }

    /** The width a perspective projection should use for this window height (the real width when not stretching). */
    public float projectionWidth(float width, float height) {
        double r = stretchRatio();
        return r > 0 && height > 0 ? (float) (height * r) : width;
    }

    /** The culling frustum's aspect: never narrower than what's drawn, or the edges of the screen would go missing. */
    public float cullAspect(float aspect) {
        double r = stretchRatio();
        return r > 0 ? (float) Math.max(aspect, r) : aspect;
    }

    // ---- freelook ------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        boolean down = mc.gui.screen() == null && KeyUtil.isDown(freelook.get());
        boolean want = freelookMode.is("Hold") ? down : (down && !keyWasDown) != freelooking;
        keyWasDown = down;
        if (want != freelooking) setFreelook(want);
    }

    @Override
    public void onDisable() {
        if (freelooking) setFreelook(false);
    }

    private void setFreelook(boolean on) {
        freelooking = on;
        if (on) {
            lookYaw = mc.player.getYRot();
            lookPitch = mc.player.getXRot();
            if (freelookThirdPerson.get() && mc.options.getCameraType().isFirstPerson()) {
                before = mc.options.getCameraType();
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        } else if (before != null) {
            mc.options.setCameraType(before);
            before = null;
        }
    }

    public boolean freelooking() { return isEnabled() && freelooking; }

    /** Mouse movement while freelooking (same scale as vanilla's Entity.turn). */
    public void freelookTurn(double dx, double dy) {
        lookYaw += (float) (dx * 0.15);
        lookPitch = Mth.clamp(lookPitch + (float) (dy * 0.15), -90f, 90f);
    }

    public float lookYaw() { return lookYaw; }
    public float lookPitch() { return lookPitch; }
}
