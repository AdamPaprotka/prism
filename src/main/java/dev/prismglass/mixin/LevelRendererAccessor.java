package dev.prismglass.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * BreakIndicators: every block being broken right now, keyed by the breaker's entity id (us included).
 * 26.3 moved the map from LevelRenderer to ClientLevel (cast {@code mc.level}); the name is kept for callers.
 */
@Mixin(ClientLevel.class)
public interface LevelRendererAccessor {
    @Accessor("destroyingBlocks") Int2ObjectMap<BlockDestructionProgress> prism$destroyingBlocks();
}
