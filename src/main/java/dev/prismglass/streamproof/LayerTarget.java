package dev.prismglass.streamproof;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Where Prism's capture-hidden visuals render: the overlay target while StreamProof runs, otherwise the game's main
 * target. Stands in for vanilla's OutputTarget, which 26.3 removed (render passes take the target's views directly).
 */
public final class LayerTarget {
    private final String name;
    private final Supplier<@Nullable RenderTarget> preferred;

    public LayerTarget(String name, Supplier<@Nullable RenderTarget> preferred) {
        this.name = name;
        this.preferred = preferred;
    }

    public RenderTarget getRenderTarget() {
        RenderTarget t = preferred.get();
        return t != null ? t : Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }

    @Override
    public String toString() { return "LayerTarget[" + name + "]"; }
}
