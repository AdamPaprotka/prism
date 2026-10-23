package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.combat.SwordBlock;
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

/** SwordBlock: hides the offhand shield and puts the main-hand sword in the block pose. */
@Mixin(ItemInHandRenderer.class)
public abstract class SwordBlockMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void prism$hideShield(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack,
                                  float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        if (hand != InteractionHand.OFF_HAND) return;
        SwordBlock sb = Prism.modules().get(SwordBlock.class);
        if (sb != null && sb.isEnabled() && sb.hidesOffhand()) ci.cancel();
    }

    /** The second renderItem call is the general branch (everything but crossbows and maps). */
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void prism$blockPose(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack,
                                 float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND) return;
        SwordBlock sb = Prism.modules().get(SwordBlock.class);
        if (sb == null || !sb.isEnabled() || !sb.blocking()) return;
        sb.applyPose(player.getMainArm(), poseStack);
    }
}
