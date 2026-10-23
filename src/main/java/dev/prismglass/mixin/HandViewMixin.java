package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.render.HandView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HandView: per-hand offsets, and skipping the equip dip. */
@Mixin(ItemInHandRenderer.class)
public abstract class HandViewMixin {
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;
    @Shadow private float mainHandHeight;
    @Shadow private float oMainHandHeight;
    @Shadow private float offHandHeight;
    @Shadow private float oOffHandHeight;

    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0))
    private void prism$handView(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack,
                                float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
        HandView hv = Prism.modules().get(HandView.class);
        if (hv == null || !hv.isEnabled()) return;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        hv.apply(hand, arm, poseStack);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void prism$noEquip(CallbackInfo ci) {
        HandView hv = Prism.modules().get(HandView.class);
        var p = Minecraft.getInstance().player;
        if (hv == null || !hv.isEnabled() || !hv.noEquip.get() || p == null || p.isHandsBusy()) return;
        mainHandItem = p.getMainHandItem();
        offHandItem = p.getOffhandItem();
        // the attack-strength cube (cooldown dip) stays: only the swap dip goes
        float strength = p.getItemSwapScale(1.0F);
        mainHandHeight = oMainHandHeight = strength * strength * strength;
        offHandHeight = oOffHandHeight = 1;
    }
}
