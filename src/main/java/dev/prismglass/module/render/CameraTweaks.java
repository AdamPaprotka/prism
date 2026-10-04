package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class CameraTweaks extends Module {
    public final BoolSetting clip = bool("Clip", true, "Third-person camera goes through blocks.");
    public final NumberSetting distance = num("Distance", 4, 1, 20, 0.5, "Third-person distance.");

    public CameraTweaks() { super("CameraTweaks", "Third-person camera options.", Category.RENDER); }

    public float distance(float vanillaClipped) {
        return clip.get() ? distance.getFloat() : Math.min(vanillaClipped, distance.getFloat());
    }
}
