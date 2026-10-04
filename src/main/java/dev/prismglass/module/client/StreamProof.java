package dev.prismglass.module.client;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;

/**
 * Hides everything Prism draws from OBS, Discord, screenshots and recordings while you still see it.
 * The work is in streamproof/Overlay; this module is just the switch.
 */
public class StreamProof extends Module {
    public final BoolSetting borderless = bool("Borderless", true,
        "Fullscreen becomes borderless fullscreen while on (needed: exclusive fullscreen would cover the hidden layer).");
    public final BoolSetting hideCursor = bool("HideCursor", true,
        "In the ClickGUI, hide the real mouse cursor (OBS/Discord draw it) and show Prism's own, which capture can't see.");

    public StreamProof() {
        super("StreamProof", "Hides Prism (HUD, ClickGUI, ESP, Xray, tracers...) from OBS/Discord/screenshots. You still see it.", Category.CLIENT);
    }
}
