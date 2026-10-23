package dev.prismglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.render.Inspect;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inspect's item layer: right after the item's display transform (translate, rotate, scale) and before the model
 * is moved to its corner (the third translate in apply), the pose is in model space centred on the item, so spins
 * and twirls turn it about its own blade or centre. Only armed while the inspected first-person hand renders.
 */
@Mixin(ItemTransform.class)
public abstract class ItemTransformMixin {
    @Inject(method = "apply", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;translate(FFF)Lorg/joml/Matrix4f;", ordinal = 2))
    private void prism$inspectItem(boolean applyLeftHandFix, PoseStack.Pose pose, CallbackInfo ci) {
        if (Inspect.itemLayer) Inspect.applyItem(pose);
    }
}
