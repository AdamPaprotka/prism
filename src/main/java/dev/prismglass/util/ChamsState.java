package dev.prismglass.util;

/**
 * Extra fields on LivingEntityRenderState (LivingEntityRenderStateMixin): the Chams colour for this entity this
 * frame (0 = none), and whether the state is a PopChams ghost (only the tinted model is drawn, no layers).
 */
public interface ChamsState {
    int prism$chams();

    void prism$setChams(int argb);

    boolean prism$ghost();

    void prism$setGhost(boolean ghost);
}
