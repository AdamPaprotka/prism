package dev.prismglass.module.movement;

import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.EntityUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Packet flight. You move with gravity off (client collisions still apply) and the normal position packet is replaced
 * by our own: the tick's movement split into {@code Factor} steps, then a tiny downward "dip" packet. Vanilla's
 * floating kick only counts while your latest move isn't going down by at least 0.03125, so the dip resets it every
 * tick without losing height (the next tick starts from above it).
 *
 * <p>Vanilla/Fabric accept up to ~10 blocks per packet and only reject moves into blocks. {@code Bounds} adds an
 * out-of-bounds packet after each tick so anticheats that track setbacks snap you to the position you just sent.
 */
public class PacketFly extends Module {
    public final ModeSetting mode = mode("Mode", "Vanilla", "Vanilla = plain packets (vanilla/Fabric servers). Bounds = adds a far packet so the server's setback lands where you are.",
        "Vanilla", "Bounds");
    public final NumberSetting speed = num("Speed", 1.0, 0.05, 9.5, 0.05, "Horizontal blocks per tick (vanilla allows up to ~10).");
    public final NumberSetting vertical = num("Vertical", 0.6, 0.05, 5, 0.05, "Up (jump) / down (sneak) blocks per tick.");
    public final NumberSetting factor = num("Factor", 1, 1, 4, 1, "Packets the tick's movement is split into.");
    public final BoolSetting antiKick = bool("AntiKick", true, "Dip packet every tick so the server never kicks you for floating.");

    private boolean sending;
    /** Server teleports (setbacks) received while on: 0 means every packet was accepted. */
    public int setbacks;
    private Vec3 tickStart;
    /** Bounds: where we meant to be when our own setback arrives (it lands on the anti-kick dip). */
    private volatile Vec3 intended;
    private volatile boolean restore;

    public PacketFly() { super("PacketFly", "Fly with custom position packets (anarchy / vanilla servers). [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onMove(MoveEvent e) {
        var p = mc.player;
        if (restore) {
            restore = false;
            Vec3 want = intended;
            if (want != null && p.position().distanceTo(want) < 0.5) p.setPos(want); // undo the dip snap, server is fine with it
        }
        double[] h = EntityUtil.directionSpeed(speed.get(), p.getYRot());
        double vy = p.input.keyPresses.jump() ? vertical.get() : p.input.keyPresses.shift() ? -vertical.get() : 0;
        tickStart = p.position();
        e.setHorizontal(h[0], h[1]);
        e.y = vy;
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
    }

    /** After the player tick (its own movement packet was cancelled): send ours. */
    @Override
    public void onPostTick() {
        var p = mc.player;
        if (tickStart == null || mc.getConnection() == null) return;
        Vec3 end = p.position();
        int n = factor.getInt();
        sending = true;
        try {
            for (int i = 1; i <= n; i++) {
                Vec3 step = tickStart.lerp(end, i / (double) n);
                send(step.x, step.y, step.z);
            }
            boolean grounded = !mc.level.noCollision(p, p.getBoundingBox().move(0, -0.05, 0));
            if (antiKick.get() && !grounded) send(end.x, end.y - 0.04, end.z);
            if (mode.is("Bounds")) {
                intended = end;
                send(end.x, end.y - 1337, end.z);
            }
        } finally {
            sending = false;
        }
        tickStart = null;
    }

    private void send(double x, double y, double z) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(x, y, z, mc.player.getYRot(), mc.player.getXRot(), false, false));
    }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (!sending && e.packet instanceof ServerboundMovePlayerPacket) e.cancel();
    }

    @Override
    public void onEnable() { setbacks = 0; }

    @Override
    public void onPacketReceive(PacketEvent e) {
        if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            setbacks++;
            if (mode.is("Bounds")) restore = true;
        }
    }

    @Override public String getInfo() { return mode.get() + (setbacks > 0 ? " " + setbacks : ""); }
}
