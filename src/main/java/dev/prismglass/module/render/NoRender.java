package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.function.Predicate;

public class NoRender extends Module {
    public final BoolSetting hurtCamS = bool("HurtCam", true, "No screen shake when hurt.");
    public final BoolSetting bobS = bool("ViewBob", false, "No view bobbing.");
    public final BoolSetting totemS = bool("TotemPop", true, "No totem animation.");
    public final BoolSetting pumpkinS = bool("Pumpkin", true, "No pumpkin/powder snow overlay.");
    public final BoolSetting portalS = bool("Portal", true, "No nether portal overlay.");
    public final BoolSetting vignetteS = bool("Vignette", true, "No vignette.");
    public final BoolSetting fireS = bool("Fire", true, "No fire overlay.");
    public final BoolSetting liquidS = bool("Liquid", true, "No underwater overlay.");
    public final BoolSetting inWallS = bool("InWall", true, "No in-wall overlay (burrow).");

    private static NoRender instance;

    public NoRender() {
        super("NoRender", "Hide annoying overlays and effects.", Category.RENDER);
        instance = this;
    }

    /** True if NoRender is on and the given option is enabled. */
    public static boolean on(Predicate<NoRender> option) {
        return instance != null && instance.isEnabled() && option.test(instance);
    }

    public boolean hurtCam() { return hurtCamS.get(); }
    public boolean bob() { return bobS.get(); }
    public boolean totem() { return totemS.get(); }
    public boolean pumpkin() { return pumpkinS.get(); }
    public boolean portal() { return portalS.get(); }
    public boolean vignette() { return vignetteS.get(); }
    public boolean fire() { return fireS.get(); }
    public boolean liquid() { return liquidS.get(); }
    public boolean inWall() { return inWallS.get(); }
}
