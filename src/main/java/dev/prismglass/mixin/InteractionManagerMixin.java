package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.player.PacketMine;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class InteractionManagerMixin {
    /** Fires for every attack: vanilla clicks and module attacks alike (Criticals, AutoWeapon...). */
    @Inject(method = "attack", at = @At("HEAD"))
    private void prism$attack(Player player, Entity target, CallbackInfo ci) {
        dev.prismglass.util.CombatUtil.attacksSent++;
        Prism.modules().onAttack(target);
        Prism.reachBudget().onAttack(target);
    }

    /** PacketMine takes over left-clicking blocks: the click queues the block and vanilla never starts mining it. */
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void prism$packetMineStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        PacketMine pm = Prism.modules().get(PacketMine.class);
        if (pm != null && pm.isEnabled() && pm.onClick(pos, direction)) cir.setReturnValue(true);
    }

    /** Holding the button would otherwise restart vanilla mining every tick. */
    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void prism$packetMineContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        PacketMine pm = Prism.modules().get(PacketMine.class);
        if (pm != null && pm.isEnabled() && pm.onClick(pos, direction)) cir.setReturnValue(true);
    }
}
