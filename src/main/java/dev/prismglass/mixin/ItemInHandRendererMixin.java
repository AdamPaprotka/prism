package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.prismglass.module.client.KeyActions;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inspect: the main hand (arm and item) swings through the inspect keyframes, pivoting around the hand. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0))
    private void prism$inspect(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack,
                               float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND) return;
        float[] pose = KeyActions.inspectPose();
        if (pose == null) return;
        float side = player.getMainArm() == HumanoidArm.RIGHT ? 1f : -1f;
        // pivot: where the hand sits on screen (vanilla's arm transform puts it about here)
        float px = side * 0.56f, py = -0.52f, pz = -0.72f;
        poseStack.translate(px + side * pose[0], py + pose[1], pz + pose[2]);
        poseStack.mulPose(Axis.XP.rotationDegrees(pose[3]));
        poseStack.mulPose(Axis.YP.rotationDegrees(side * pose[4]));
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * pose[5]));
        poseStack.translate(-px, -py, -pz);
    }
}
