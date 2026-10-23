package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.prismglass.Prism;
import dev.prismglass.module.movement.EntityControl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** EntityControl: pigs and striders steer without their stick (the saddle is still needed to sit on them). */
@Mixin({Pig.class, Strider.class})
public abstract class EntityControlStickMixin {
    @ModifyExpressionValue(method = "getControllingPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isHolding(Lnet/minecraft/world/item/Item;)Z"))
    private boolean prism$noStick(boolean holding) {
        EntityControl ec = Prism.modules().get(EntityControl.class);
        return holding || (ec != null && ec.noStick.get() && ec.controls((Entity) (Object) this));
    }
}
