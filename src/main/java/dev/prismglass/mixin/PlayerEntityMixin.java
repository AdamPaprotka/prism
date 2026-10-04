package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.prismglass.Prism;
import dev.prismglass.module.movement.SafeWalk;
import dev.prismglass.module.movement.Scaffold;
import dev.prismglass.module.player.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerEntityMixin {
    private boolean prism$isSelf() {
        return (Object) this == Minecraft.getInstance().player;
    }

    @ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
    private double prism$entityReach(double original) {
        if (!prism$isSelf()) return original;
        Reach r = Prism.modules().get(Reach.class);
        return r != null && r.isEnabled() ? r.entity(original) : original;
    }

    @ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
    private double prism$blockReach(double original) {
        if (!prism$isSelf()) return original;
        Reach r = Prism.modules().get(Reach.class);
        return r != null && r.isEnabled() ? r.block(original) : original;
    }

    @ModifyReturnValue(method = "isStayingOnGroundSurface", at = @At("RETURN"))
    private boolean prism$safeWalk(boolean original) {
        if (original || !prism$isSelf()) return original;
        SafeWalk sw = Prism.modules().get(SafeWalk.class);
        Scaffold sc = Prism.modules().get(Scaffold.class);
        return (sw != null && sw.isEnabled()) || (sc != null && sc.isEnabled() && sc.safeWalk());
    }
}
