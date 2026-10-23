package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.player.PacketMine;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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

    /** VeinMiner: a block the client just finished breaking (read before vanilla clears it). */
    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void prism$veinMiner(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        var vm = Prism.modules().get(dev.prismglass.module.world.VeinMiner.class);
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (vm != null && vm.isEnabled() && level != null) vm.onBroken(pos.immutable(), level.getBlockState(pos));
    }

    @Unique private net.minecraft.world.entity.player.Input prism$sneakRestore;

    /**
     * NoInteract: right-clicks on listed blocks do nothing, or with PlaceInstead and a block in hand place against
     * them the way sneaking does (the server only skips the block's use when it sees you sneak, so the shift input is
     * sent for the click and the real one right after).
     */
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void prism$noInteract(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        var ni = Prism.modules().get(dev.prismglass.module.player.NoInteract.class);
        if (ni == null || !ni.isEnabled() || player.isShiftKeyDown() || !ni.blocks(player.level().getBlockState(hit.getBlockPos()))) return;
        if (ni.placeInstead.get() && player.getItemInHand(hand).getItem() instanceof BlockItem) {
            var cur = player.input.keyPresses;
            var sneak = new net.minecraft.world.entity.player.Input(cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(), true, cur.sprint());
            player.connection.send(new ServerboundPlayerInputPacket(sneak));
            player.input.keyPresses = sneak;
            prism$sneakRestore = cur;
            return;
        }
        cir.setReturnValue(InteractionResult.PASS);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void prism$noInteractUnsneak(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (prism$sneakRestore == null) return;
        player.input.keyPresses = prism$sneakRestore;
        player.connection.send(new ServerboundPlayerInputPacket(prism$sneakRestore));
        prism$sneakRestore = null;
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void prism$noInteractEntity(Player player, Entity entity, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        var ni = Prism.modules().get(dev.prismglass.module.player.NoInteract.class);
        if (ni != null && ni.isEnabled() && ni.blocks(entity)) cir.setReturnValue(InteractionResult.PASS);
    }
}
