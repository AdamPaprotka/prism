package dev.prismglass.mixin;

import dev.prismglass.Prism;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class InteractionManagerMixin {
    /** Fires for every attack: vanilla clicks and module attacks alike (Criticals, AutoWeapon...). */
    @Inject(method = "attack", at = @At("HEAD"))
    private void prism$attack(Player player, Entity target, CallbackInfo ci) {
        dev.prismglass.util.CombatUtil.attacksSent++;
        Prism.modules().onAttack(target);
        Prism.reachBudget().onAttack(target);
    }
}
