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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.protocol.PacketFlow;

@Mixin(Connection.class)
public abstract class ClientConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void prism$send(Packet<?> packet, ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
        if (dev.prismglass.manager.BotPlayers.isBot((Connection) (Object) this)) return; // bots' own traffic
        if (Prism.onPacketSend(packet)) ci.cancel();
    }

    /** Proxies: the active proxy's handler goes first in every new client->server pipeline, before the connect. */
    @Inject(method = "configureSerialization", at = @At("HEAD"))
    private static void prism$proxy(ChannelPipeline pipeline, PacketFlow flow, boolean memoryOnly, BandwidthDebugMonitor monitor, CallbackInfo ci) {
        if (flow != PacketFlow.CLIENTBOUND || memoryOnly || Prism.proxies() == null) return;
        ChannelHandler h = Prism.proxies().handler();
        if (h != null) pipeline.addFirst("prism_proxy", h);
    }

    @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
    private static <T extends PacketListener> void prism$receive(Packet<T> packet, PacketListener listener, CallbackInfo ci) {
        boolean bot = listener instanceof dev.prismglass.manager.BotPlayers.Marker;
        if (!bot && (listener instanceof ClientGamePacketListener || listener instanceof net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener))
            dev.prismglass.scan.ServerIntel.onReceive(listener, packet); // passive, config phase too
        if (listener instanceof ClientGamePacketListener && !bot && Prism.onPacketReceive(packet)) ci.cancel();
    }
}
