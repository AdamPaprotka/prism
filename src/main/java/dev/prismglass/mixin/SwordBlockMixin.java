package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.combat.SwordBlock;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SwordBlock: hides the offhand shield and puts the main-hand sword in the block pose. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class SwordBlockMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void prism$hideShield(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
                                  InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                  SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        if (hand != InteractionHand.OFF_HAND) return;
        SwordBlock sb = Prism.modules().get(SwordBlock.class);
        if (sb != null && sb.isEnabled() && sb.hidesOffhand()) ci.cancel();
    }

    /**
     * The second item submit is the general branch (everything but crossbows and maps). 26.3 submits the hand's
     * prepared ItemStackRenderState instead of calling renderItem.
     */
    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void prism$blockPose(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
                                 InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                 SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND) return;
        SwordBlock sb = Prism.modules().get(SwordBlock.class);
        if (sb == null || !sb.isEnabled() || !sb.blocking()) return;
        sb.applyPose(playerState.avatarRenderState.mainArm, poseStack);
    }
}
