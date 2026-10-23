package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.player.InventoryTweaks;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** InventoryTweaks AntiDrop: Q / ctrl+Q over a slot in any screen. */
@Mixin(MultiPlayerGameMode.class)
public abstract class AntiDropScreenMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void prism$antiDrop(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (input != ContainerInput.THROW || slot < 0 || slot >= player.containerMenu.slots.size()) return;
        InventoryTweaks it = Prism.modules().get(InventoryTweaks.class);
        if (it != null && it.isEnabled() && it.keep(player.containerMenu.getSlot(slot).getItem())) ci.cancel();
    }
}
