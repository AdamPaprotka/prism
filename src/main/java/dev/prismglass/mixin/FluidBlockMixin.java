package dev.prismglass.mixin;

import dev.prismglass.Prism;
import dev.prismglass.module.movement.Jesus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LiquidBlock.class)
public abstract class FluidBlockMixin {
    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void prism$jesus(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (Prism.modules() == null) return;
        Jesus jesus = Prism.modules().get(Jesus.class);
        if (jesus != null && jesus.isEnabled()) {
            VoxelShape shape = jesus.shape(state, pos, context);
            if (shape != null) cir.setReturnValue(shape);
        }
    }
}
