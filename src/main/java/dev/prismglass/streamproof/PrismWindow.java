package dev.prismglass.streamproof;

/** Added to Minecraft's Window by WindowMixin: its real fullscreen state (26.3 no longer exposes it). */
public interface PrismWindow {
    /** The window is fullscreen right now (exclusive or borderless), not just requested. */
    boolean prism$fullscreen();

    /** Fullscreen is the borderless window that covers the monitor (what StreamProof needs). */
    boolean prism$borderless();
}
