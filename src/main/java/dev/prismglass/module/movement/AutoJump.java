package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class AutoJump extends Module {
    public final BoolSetting onlyMoving = bool("OnlyMoving", true, "Only jump while moving.");
    public AutoJump() { super("AutoJump", "Jump automatically (real input, safe).", Category.MOVEMENT); }
}
