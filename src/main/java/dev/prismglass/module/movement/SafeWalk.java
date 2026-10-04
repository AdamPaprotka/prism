package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class SafeWalk extends Module {
    public SafeWalk() { super("SafeWalk", "Never walk off block edges (vanilla sneak-edge logic, safe).", Category.MOVEMENT); }
}
