package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;

/**
 * Server teleports and rotation packets stop turning your camera. The reply the server waits for still carries
 * its rotation (NoRotateMixin puts yours back right after it went out), so the teleport is confirmed normally and
 * your next move packet just turns back.
 */
public class NoRotate extends Module {
    public NoRotate() { super("NoRotate", "Server packets can't turn your camera.", Category.PLAYER); }
}
