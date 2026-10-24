package dev.prismglass.mixin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * GuiRenderer's private Draw record. 26.3 dropped its vertexBuffer/baseVertex/mode/indexCount components for a
 * StagedVertexBuffer.Draw handle: the index count is now {@code stagedBuffer.getExecuteInfo(draw).indexCount()}.
 */
@Mixin(targets = "net.minecraft.client.gui.render.GuiRenderer$Draw")
public interface GuiDrawAccessor {
    @Accessor("draw")
    StagedVertexBuffer.Draw prism$draw();
}
