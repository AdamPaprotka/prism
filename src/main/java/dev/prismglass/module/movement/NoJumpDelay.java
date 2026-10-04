package dev.prismglass.module.movement;

import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;

public class NoJumpDelay extends Module {
    public NoJumpDelay() { super("NoJumpDelay", "No 10-tick delay between held jumps. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() { mc.player.noJumpDelay = 0; }
}
