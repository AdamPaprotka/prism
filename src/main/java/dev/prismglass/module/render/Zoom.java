package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class Zoom extends Module {
    public final NumberSetting factor = num("Factor", 4, 1.5, 20, 0.5, "Zoom amount.");
    public final BoolSetting smooth = bool("Smooth", true, "Animate in/out.");
    private float anim = 1f;
    private long last = System.nanoTime();

    public Zoom() {
        super("Zoom", "Optifine-style zoom (bind with Hold mode).", Category.RENDER);
        bindMode.set("Hold");
        drawn.set(false);
    }

    public float apply(float fov) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        float target = isEnabled() ? factor.getFloat() : 1f;
        anim = smooth.get() ? anim + (target - anim) * Math.min(1f, dt * 14f) : target;
        return anim <= 1.001f ? fov : fov / anim;
    }
}
