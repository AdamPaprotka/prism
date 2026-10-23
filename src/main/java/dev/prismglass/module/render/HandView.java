package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/**
 * First-person hand placement: move, turn and scale each hand (pivoting where the hand sits, so scaling shrinks it
 * in place), slow down or speed up the swing animation, and skip the dip when switching items. All client-side
 * visuals: what the server sees doesn't change.
 */
public class HandView extends Module {
    public final NumberSetting mainX = num("MainX", 0, -1, 1, 0.01, "Main hand: right (+) / left (-).");
    public final NumberSetting mainY = num("MainY", 0, -1, 1, 0.01, "Main hand: up / down.");
    public final NumberSetting mainZ = num("MainZ", 0, -1, 1, 0.01, "Main hand: toward you (+) / away (-).");
    public final NumberSetting mainRotX = num("MainRotX", 0, -180, 180, 1, "Main hand: tilt.");
    public final NumberSetting mainRotY = num("MainRotY", 0, -180, 180, 1, "Main hand: turn.");
    public final NumberSetting mainRotZ = num("MainRotZ", 0, -180, 180, 1, "Main hand: roll.");
    public final NumberSetting mainScale = num("MainScale", 1, 0.1, 2, 0.05, "Main hand size.");
    public final NumberSetting offX = num("OffX", 0, -1, 1, 0.01, "Offhand: outward (+) / inward (-).");
    public final NumberSetting offY = num("OffY", 0, -1, 1, 0.01, "Offhand: up / down.");
    public final NumberSetting offZ = num("OffZ", 0, -1, 1, 0.01, "Offhand: toward you (+) / away (-).");
    public final NumberSetting offRotX = num("OffRotX", 0, -180, 180, 1, "Offhand: tilt.");
    public final NumberSetting offRotY = num("OffRotY", 0, -180, 180, 1, "Offhand: turn.");
    public final NumberSetting offRotZ = num("OffRotZ", 0, -180, 180, 1, "Offhand: roll.");
    public final NumberSetting offScale = num("OffScale", 1, 0.1, 2, 0.05, "Offhand size.");
    public final NumberSetting swingSpeed = num("SwingSpeed", 1, 0.25, 4, 0.05, "Swing animation speed (2 = twice as fast). Visual only.");
    public final BoolSetting noEquip = bool("NoEquipAnimation", false, "No lowering and raising when you switch items.");

    public HandView() { super("HandView", "Move, turn and scale your first-person hands; swing speed; no equip dip.", Category.RENDER); }

    /** HandViewMixin: right after renderArmWithItem's pushPose. */
    public void apply(InteractionHand hand, HumanoidArm arm, PoseStack pose) {
        boolean main = hand == InteractionHand.MAIN_HAND;
        float side = arm == HumanoidArm.RIGHT ? 1f : -1f;
        float x = (main ? mainX : offX).getFloat(), y = (main ? mainY : offY).getFloat(), z = (main ? mainZ : offZ).getFloat();
        float rx = (main ? mainRotX : offRotX).getFloat(), ry = (main ? mainRotY : offRotY).getFloat(), rz = (main ? mainRotZ : offRotZ).getFloat();
        float s = (main ? mainScale : offScale).getFloat();
        // pivot where the hand sits (vanilla's applyItemArmTransform position)
        float px = side * 0.56f, py = -0.52f, pz = -0.72f;
        pose.translate(px + side * x, py + y, pz + z);
        if (rx != 0) pose.mulPose(Axis.XP.rotationDegrees(rx));
        if (ry != 0) pose.mulPose(Axis.YP.rotationDegrees(side * ry));
        if (rz != 0) pose.mulPose(Axis.ZP.rotationDegrees(side * rz));
        if (s != 1) pose.scale(s, s, s);
        pose.translate(-px, -py, -pz);
    }

    /** HandViewMixin: the local player's swing length in ticks. */
    public int swingDuration(int vanilla) {
        return Math.max(1, Math.round(vanilla / swingSpeed.getFloat()));
    }
}
