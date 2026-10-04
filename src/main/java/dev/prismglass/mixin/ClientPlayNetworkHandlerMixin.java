package dev.prismglass.mixin;

import dev.prismglass.Prism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void prism$command(String message, CallbackInfo ci) {
        if (Prism.commands().handle(message)) {
            Minecraft.getInstance().gui.getChat().addRecentChat(message);
            ci.cancel();
        }
    }
}
