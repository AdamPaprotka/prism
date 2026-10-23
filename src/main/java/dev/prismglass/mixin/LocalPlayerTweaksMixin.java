package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.prismglass.Prism;
import dev.prismglass.module.player.NoMiningTrace;
import dev.prismglass.module.player.Portals;
import java.util.function.Predicate;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerTweaksMixin {
    /** Portals: standing in a nether portal doesn't close the open screen. */
    @WrapOperation(method = "handlePortalTransitionEffect", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;isAllowedInPortal()Z"))
    private boolean prism$portalScreens(Screen screen, Operation<Boolean> original) {
        Portals portals = Prism.modules().get(Portals.class);
        return (portals != null && portals.isEnabled()) || original.call(screen);
    }

    /** NoMiningTrace: the crosshair's entity pick skips entities while mining, so the block behind them gets hit. */
    @ModifyArg(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"),
        index = 4)
    private static Predicate<Entity> prism$noMiningTrace(Predicate<Entity> original) {
        NoMiningTrace nmt = Prism.modules().get(NoMiningTrace.class);
        if (nmt == null || !nmt.isEnabled() || !nmt.active()) return original;
        return original.and(e -> !nmt.ignores(e));
    }
}
