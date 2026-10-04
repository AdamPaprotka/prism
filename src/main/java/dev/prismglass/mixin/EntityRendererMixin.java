package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.render.Nametags;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    /** Hide vanilla name labels for players while our glass nametags are on. */
    @Inject(method = "shouldShowName", at = @At("HEAD"), cancellable = true)
    private void prism$hideLabel(Entity entity, double distance, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Player)) return;
        Nametags n = Prism.modules().get(Nametags.class);
        if (n != null && n.isEnabled()) cir.setReturnValue(false);
    }
}
