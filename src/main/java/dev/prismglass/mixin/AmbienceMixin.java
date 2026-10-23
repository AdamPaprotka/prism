package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.prismglass.Prism;
import dev.prismglass.module.render.Ambience;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ambience: environment colours and fog distances, as the client's renderers read them. */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class AmbienceMixin {
    @ModifyReturnValue(method = "getValue", at = @At("RETURN"))
    private Object prism$ambience(Object original, EnvironmentAttribute<Object> attribute, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return original; // the integrated server probes too
        Ambience a = Prism.modules() == null ? null : Prism.modules().get(Ambience.class);
        return a != null && a.isEnabled() ? a.value(attribute, original) : original;
    }
}
