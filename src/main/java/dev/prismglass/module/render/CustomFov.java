package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class CustomFov extends Module {
    public final NumberSetting fov = num("FOV", 110, 30, 170, 1, "Field of view.");
    public final BoolSetting dynamic = bool("KeepDynamic", false, "Keep sprint/bow FOV changes on top.");

    public CustomFov() { super("CustomFov", "Set any FOV.", Category.RENDER); }

    public float apply(float vanilla) {
        if (!dynamic.get()) return fov.getFloat();
        float base = mc.options.fov().get();
        return vanilla / base * fov.getFloat();
    }
}
