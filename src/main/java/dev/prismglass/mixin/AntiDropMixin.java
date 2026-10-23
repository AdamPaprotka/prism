package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.player.InventoryTweaks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** InventoryTweaks AntiDrop: Q in the world (AntiDropScreenMixin covers Q over a slot in screens). */
@Mixin(LocalPlayer.class)
public abstract class AntiDropMixin {
    @org.spongepowered.asm.mixin.Unique
    private static boolean prism$keep(ItemStack stack) {
        InventoryTweaks it = Prism.modules().get(InventoryTweaks.class);
        return it != null && it.isEnabled() && it.keep(stack);
    }

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void prism$antiDrop(boolean all, CallbackInfoReturnable<Boolean> cir) {
        if (prism$keep(((LocalPlayer) (Object) this).getMainHandItem())) cir.setReturnValue(false);
    }
}
