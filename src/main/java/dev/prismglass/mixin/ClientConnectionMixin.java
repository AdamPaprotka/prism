package dev.prismglass.mixin;

import dev.prismglass.Prism;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ClientConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void prism$send(Packet<?> packet, ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
        if (Prism.onPacketSend(packet)) ci.cancel();
    }

    @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
    private static <T extends PacketListener> void prism$receive(Packet<T> packet, PacketListener listener, CallbackInfo ci) {
        if (listener instanceof ClientGamePacketListener && Prism.onPacketReceive(packet)) ci.cancel();
    }
}
