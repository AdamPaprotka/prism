package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.player.InventoryTweaks;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * InventoryTweaks AntiDrop: Q in the world (AntiDropScreenMixin covers Q over a slot in screens).
 * 26.3 moved the drop key from LocalPlayer.drop(boolean) to MultiPlayerGameMode.dropItem(LocalPlayer, boolean).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class AntiDropMixin {
    @org.spongepowered.asm.mixin.Unique
    private static boolean prism$keep(ItemStack stack) {
        InventoryTweaks it = Prism.modules().get(InventoryTweaks.class);
        return it != null && it.isEnabled() && it.keep(stack);
    }

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void prism$antiDrop(LocalPlayer player, boolean all, CallbackInfo ci) {
        if (prism$keep(player.getMainHandItem())) ci.cancel();
    }
}
