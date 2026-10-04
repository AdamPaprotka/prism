package dev.prismglass.mixin;

import dev.prismglass.manager.MovementHooks;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void prism$afterInput(CallbackInfo ci) {
        MovementHooks.afterInputTick((ClientInput) (Object) this);
    }
}
