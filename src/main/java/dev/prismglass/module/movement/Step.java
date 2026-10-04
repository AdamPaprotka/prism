package dev.prismglass.module.movement;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Walk up blocks instantly.
 *
 * <p>"NCP": NoCheatPlus checks every position packet as one tick of movement, so a 1-block step is sent as the
 * jump it would have been: the jump arc's first two positions (+0.42, +0.753) go out before the step's own packet.
 * Up to 1 block (a jump can't reach higher in three ticks). [Grim-unsafe: each packet is a tick for Grim]
 * "Vanilla" just raises the step height [unsafe on any AC].
 */
public class Step extends Module {
    public final ModeSetting mode = mode("Mode", "NCP", "NCP = sends the jump arc for 1-block steps. Vanilla = plain step height. [Grim-unsafe both]", "NCP", "Vanilla");
    public final NumberSetting height = num("Height", 1.0, 0.6, 2.5, 0.1, "Max step height (NCP mode: up to 1).");

    private static final double[] JUMP_ARC = {0.41999998688698, 0.7531999805212};
    private double startX, startY, startZ;
    private boolean startGround;

    public Step() {
        super("Step", "Walk up full blocks instantly.", Category.MOVEMENT);
    }

    public float height(float original) {
        if (mc.player.isShiftKeyDown()) return original;
        float h = height.getFloat();
        if (mode.is("NCP")) h = Math.min(h, 1.0f);
        return Math.max(original, h);
    }

    @Override
    public void onTick() {
        // where we are before this tick's movement (what the server last got)
        startX = mc.player.getX();
        startY = mc.player.getY();
        startZ = mc.player.getZ();
        startGround = mc.player.onGround();
    }

    /** Right before the movement packet: if we just stepped up more than vanilla's 0.6, send the jump arc first. */
    public void beforeSend() {
        if (!mode.is("NCP")) return;
        var p = mc.player;
        double rise = p.getY() - startY;
        if (!startGround || !p.onGround() || rise <= 0.6 || rise > 1.0 + 1e-6) return;
        for (double dy : JUMP_ARC) {
            p.connection.send(new ServerboundMovePlayerPacket.Pos(startX, startY + dy, startZ, false, p.horizontalCollision));
        }
    }

    @Override public String getInfo() { return mode.get() + " " + height.display(); }
}
