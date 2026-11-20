package dev.prismglass.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.prismglass.xray.PieceRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * StructureFinder's exact matching runs vanilla piece code into a recording level that has no chunks: skip the
 * fence/torch post-processing mark while recording (the level hands out no chunk to mark).
 */
@Mixin(StructurePiece.class)
public abstract class StructureRecordMixin {
    @WrapWithCondition(method = "placeBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;markPosForPostProcessing(Lnet/minecraft/core/BlockPos;)V"))
    private boolean prism$skipWhileRecording(ChunkAccess chunk, BlockPos pos) {
        return !PieceRecorder.recording();
    }
}
