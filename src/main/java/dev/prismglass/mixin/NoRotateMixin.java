package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.player.NoRotate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoRotate: remember the camera before a teleport / rotate packet is handled and put it back after (after the
 * confirming move packet went out with the server's rotation). HEAD also runs once on the netty thread before the
 * handler hops to the main thread; that early copy is simply overwritten.
 */
@Mixin(ClientPacketListener.class)
public abstract class NoRotateMixin {
    @Unique private float prism$yaw, prism$pitch;

    @Inject(method = {"handleMovePlayer", "handleRotatePlayer"}, at = @At("HEAD"))
    private void prism$saveRot(CallbackInfo ci) {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        prism$yaw = p.getYRot();
        prism$pitch = p.getXRot();
    }

    @Inject(method = {"handleMovePlayer", "handleRotatePlayer"}, at = @At("TAIL"))
    private void prism$restoreRot(CallbackInfo ci) {
        var p = Minecraft.getInstance().player;
        NoRotate nr = Prism.modules().get(NoRotate.class);
        if (p == null || nr == null || !nr.isEnabled()) return;
        p.setYRot(prism$yaw);
        p.setXRot(prism$pitch);
        p.yRotO = prism$yaw;
        p.xRotO = prism$pitch;
    }
}
