package dev.prismglass.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** BreakIndicators: every block being broken right now, keyed by the breaker's entity id (us included). */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("destroyingBlocks") Int2ObjectMap<BlockDestructionProgress> prism$destroyingBlocks();
}
