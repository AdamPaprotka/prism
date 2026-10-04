package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

/** Ignores levitation client-side. [Grim-unsafe] */
public class AntiLevitation extends Module {
    public AntiLevitation() { super("AntiLevitation", "Ignore shulker levitation. [Grim-unsafe]", Category.MOVEMENT); }
}
