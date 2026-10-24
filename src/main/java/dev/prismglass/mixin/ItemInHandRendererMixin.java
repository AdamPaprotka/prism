package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.render.HandView;
import dev.prismglass.module.render.Inspect;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HandView offsets + Inspect, at the start of each hand's pose (26.3: ItemInHandRenderer.renderArmWithItem became
 * FirstPersonHandsAndItemsRenderer.submitArmWithItem, which reads the player from its render state). HandView's
 * per-hand offset goes first, then Inspect moves the (offset) hand through the inspect pose, pivoting around the
 * hand, and the item drawn inside gets Inspect's item layer (ItemTransformMixin) until the method returns.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0))
    private void prism$handPose(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
                                InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        // pushPose only runs with an avatar state, whose mainArm is the player's
        HumanoidArm mainArm = playerState.avatarRenderState.mainArm;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
        HandView hv = Prism.modules().get(HandView.class);
        if (hv != null && hv.isEnabled()) hv.apply(hand, arm, poseStack);
        Inspect inspect = Prism.modules().get(Inspect.class);
        if (inspect == null || !inspect.inspecting()) { Inspect.itemLayer = false; return; }
        inspect.applyHand(hand, arm, poseStack);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void prism$inspectEnd(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
                                  InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                  SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        Inspect.itemLayer = false;
    }
}
