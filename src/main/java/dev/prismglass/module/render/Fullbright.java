package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

/** Max brightness via a client-only night-vision strength override (no gamma hacks, no flicker). */
public class Fullbright extends Module {
    public Fullbright() { super("Fullbright", "See in the dark.", Category.RENDER); }
}
