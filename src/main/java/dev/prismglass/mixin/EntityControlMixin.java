package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.prismglass.Prism;
import dev.prismglass.module.movement.EntityControl;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** EntityControl: a horse-like counts as saddled when deciding who steers it. */
@Mixin(AbstractHorse.class)
public abstract class EntityControlMixin {
    @ModifyExpressionValue(method = "getControllingPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/equine/AbstractHorse;isSaddled()Z"))
    private boolean prism$noSaddle(boolean saddled) {
        EntityControl ec = Prism.modules().get(EntityControl.class);
        return saddled || (ec != null && ec.noSaddle.get() && ec.controls((AbstractHorse) (Object) this));
    }
}
