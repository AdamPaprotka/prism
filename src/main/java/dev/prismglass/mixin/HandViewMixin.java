package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.render.HandView;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HandView: skipping the equip dip. 26.3 moved the held-item/equip-height state out of the hand renderer into the
 * player's FirstPersonHandsAndItems (ticked with the player); the per-hand offsets are applied in
 * ItemInHandRendererMixin, which hooks the hand renderer.
 */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class HandViewMixin {
    @Shadow private net.minecraft.world.item.ItemStack mainHandItem;
    @Shadow private net.minecraft.world.item.ItemStack offHandItem;
    @Shadow private float mainHandHeight;
    @Shadow private float oMainHandHeight;
    @Shadow private float offHandHeight;
    @Shadow private float oOffHandHeight;

    @Inject(method = "tick", at = @At("TAIL"))
    private void prism$noEquip(LocalPlayer p, CallbackInfo ci) {
        HandView hv = Prism.modules().get(HandView.class);
        if (hv == null || !hv.isEnabled() || !hv.noEquip.get() || p.isHandsBusy()) return;
        mainHandItem = p.getMainHandItem();
        offHandItem = p.getOffhandItem();
        // the attack-strength cube (cooldown dip) stays: only the swap dip goes
        float strength = p.getItemSwapScale(1.0F);
        mainHandHeight = oMainHandHeight = strength * strength * strength;
        offHandHeight = oOffHandHeight = 1;
    }
}
