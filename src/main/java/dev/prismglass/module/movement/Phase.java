package dev.prismglass.module.movement;

import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.EntityUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Walk through blocks. Turns off the player's block collision ({@code Entity.noPhysics}, forced in EntityMixin because
 * {@code Player.tick} resets it every tick) and flies you with WASD / jump / sneak, so you pass through walls and
 * floors instead of colliding.
 *
 * <p>The server still has the final say. Even plain Paper, with no anticheat, pulls you back out when you send it a
 * position that is inside a block. <b>Setback</b> (on by default) stops that: while your body is inside a block the
 * outgoing position packets are held, so the server keeps you at the spot you entered from and only sees you again
 * once you reach open space on the other side. It then looks like one short hop from the entry to the pocket, which a
 * no-anticheat server accepts. Keep the hops short: if you phase a long way while hidden, the jump when you come out
 * can trip the server's "moved too quickly" check. Grim / NCP / Vulcan predict every tick and set you back regardless
 * [very unsafe on any anticheat].
 */
public class Phase extends Module {
    public final NumberSetting speed = num("Speed", 0.25, 0.05, 2, 0.05, "Horizontal speed through blocks (blocks per tick).");
    public final NumberSetting vSpeed = num("VerticalSpeed", 0.25, 0.05, 2, 0.05, "Up / down speed on jump / sneak.");
    public final BoolSetting setback = bool("Setback", true, "Hold your position packets while you are inside a block, so the server can't pull you out. Turn off on a truly open server to send real positions.");
    public final BoolSetting noFall = bool("NoFall", true, "Keep your fall distance at zero so you take no fall damage when you stop.");

    public Phase() { super("Phase", "Walk through blocks: no collision + fly. [no-anticheat servers only]", Category.MOVEMENT); }

    @Override
    public void onDisable() {
        if (mc.player != null) mc.player.noPhysics = false;
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        if (noFall.get()) mc.player.resetFallDistance(); // noPhysics itself is forced in EntityMixin at move() time
    }

    @Override
    public void onMove(MoveEvent e) {
        double[] d = EntityUtil.directionSpeed(speed.get(), mc.player.getYRot());
        e.setHorizontal(d[0], d[1]);
        double y = 0;
        if (mc.player.input.keyPresses.jump()) y += vSpeed.get();
        if (mc.player.input.keyPresses.shift()) y -= vSpeed.get();
        e.y = y;                                  // full control of the movement vector = no gravity, you hover when still
        mc.player.setDeltaMovement(0, 0, 0);
    }

    /** True while the player's box is clipping a block (i.e. you're inside the wall, not in open space). */
    private boolean insideBlock() {
        return !mc.level.noCollision(mc.player, mc.player.getBoundingBox());
    }

    @Override
    public void onPacketSend(PacketEvent e) {
        // don't tell the server where we are while we're inside a block: it keeps us at the entry, then sees the exit
        if (setback.get() && e.packet instanceof ServerboundMovePlayerPacket && insideBlock()) e.cancel();
    }

    @Override public String getInfo() { return insideBlock() ? "§cin wall" : null; }
}
