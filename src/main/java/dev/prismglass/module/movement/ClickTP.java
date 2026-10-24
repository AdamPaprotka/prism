package dev.prismglass.module.movement;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.BlockUtil;
import dev.prismglass.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Right-click a block with an empty hand to teleport on top of it. The vanilla server takes a move packet while it's
 * under ~10 blocks from where you are (100 blocks squared per packet) and your box can slide along the line without
 * hitting anything (it replays the move with collision, else "moved wrongly" puts you back). So the trip goes out as
 * Hop-sized position packets along a straight, clear line; a blocked line is refused instead of sent. Anticheats
 * that simulate movement (Grim, NCP) see this. [Grim-unsafe]
 */
public class ClickTP extends Module {
    public final NumberSetting range = num("Range", 60, 5, 200, 1, "How far you can click.");
    public final NumberSetting hop = num("Hop", 8, 2, 9.5, 0.5, "Blocks per position packet (vanilla accepts up to ~10).");
    public final BoolSetting emptyHand = bool("EmptyHand", true, "Only with nothing in your main hand.");

    private boolean wasDown;

    public ClickTP() { super("ClickTP", "Right-click a block to teleport onto it. [Grim-unsafe]", Category.MOVEMENT); }

    @Override
    public void onTick() {
        boolean down = mc.options.keyUse.isDown() && mc.gui.screen() == null;
        boolean pressed = down && !wasDown;
        wasDown = down;
        if (!pressed || (emptyHand.get() && !mc.player.getMainHandItem().isEmpty())) return;
        HitResult hit = mc.player.pick(range.get(), 1f, false);
        if (!(hit instanceof BlockHitResult b) || hit.getType() == HitResult.Type.MISS) return;
        BlockPos stand = b.getBlockPos().relative(b.getDirection());
        // clicked a side: stand on the ground below that spot if there is room
        if (b.getDirection() != Direction.UP && BlockUtil.isReplaceable(stand.below())) stand = stand.below();
        if (!BlockUtil.isReplaceable(stand) || !BlockUtil.isReplaceable(stand.above())) { ChatUtil.error("ClickTP: no room there"); return; }
        Vec3 to = new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        if (!clear(mc.player.position(), to)) { ChatUtil.error("ClickTP: something's in the way"); return; }
        while (mc.options.keyUse.consumeClick()) {} // don't also use the block we clicked
        teleport(to);
    }

    /** Feet, waist and head lines are free of blocks (the server sweeps the whole box). */
    private boolean clear(Vec3 from, Vec3 to) {
        for (double dy : new double[]{0.1, 1.0, 1.7}) {
            var r = mc.level.clip(new ClipContext(from.add(0, dy, 0), to.add(0, dy, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (r.getType() != HitResult.Type.MISS) return false;
        }
        return true;
    }

    public void teleport(Vec3 to) {
        Vec3 from = mc.player.position();
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / hop.get()));
        for (int i = 1; i <= steps; i++) {
            Vec3 p = from.lerp(to, (double) i / steps);
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(p, i == steps, false));
        }
        mc.player.setPos(to);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.resetFallDistance();
    }
}
