package dev.prismglass.module.player;

import net.minecraft.client.multiplayer.chat.ChatRestriction.Action;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.component.predicates.DataComponentPredicate.Type;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Faster mining.
 * <ul>
 *   <li>Damage: removes the 5-tick cooldown between blocks and finishes at {@code Finish} progress. [Grim FastBreak above vanilla]</li>
 *   <li>Packet: sends start+stop once the block would be broken by vanilla timing, while you're free to look away
 *       (break time is still vanilla, so it is timing-legal).</li>
 * </ul>
 */
public class SpeedMine extends Module {
    public final ModeSetting mode = mode("Mode", "Packet", "Packet keeps vanilla timing.", "Packet", "Damage");
    public final NumberSetting finish = num("Finish", 0.7, 0.5, 1, 0.05, "Damage mode: finish at this progress.");
    private BlockPos packetPos;
    private Direction packetFace = Direction.UP;
    private long packetStart;
    private boolean wasPressed;

    public SpeedMine() { super("SpeedMine", "Mine faster.", Category.PLAYER); }

    @Override public void onDisable() { packetPos = null; }

    @Override
    public void onTick() {
        var im = mc.gameMode;
        if (mode.is("Damage")) {
            im.destroyDelay = 0;
            if (im.isDestroying && im.destroyProgress >= finish.get()) im.destroyProgress = 1f;
            return;
        }
        boolean pressed = mc.options.keyAttack.isDown();
        if (pressed && !wasPressed && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            if (!BlockUtil.isUnbreakable(pos) && !pos.equals(packetPos)) {
                mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, bhr.getDirection()));
                mc.player.swing(InteractionHand.MAIN_HAND);
                packetPos = pos;
                packetFace = bhr.getDirection();
                packetStart = System.currentTimeMillis();
            }
        }
        wasPressed = pressed;
        if (packetPos != null) {
            if (BlockUtil.isReplaceable(packetPos)) { packetPos = null; return; }
            float delta = BlockUtil.state(packetPos).getDestroyProgress(mc.player, mc.level, packetPos);
            long needed = (long) (50 / Math.max(delta, 0.0001f));
            if (System.currentTimeMillis() - packetStart >= needed && Prism.guard().canPlace()) {
                // finish on a face we can see, with the rotation on the block (PositionBreakA / RotationBreak)
                Direction face = BlockUtil.isFaceVisible(packetPos, packetFace, mc.player.getEyePosition()) ? packetFace : BlockUtil.breakFace(packetPos);
                if (face == null || !BlockUtil.readyToDig(packetPos, 25)) return;
                mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, packetPos, face));
                mc.player.swing(InteractionHand.MAIN_HAND);
                packetStart = System.currentTimeMillis() + 1500;
            }
        }
    }

    @Override
    public void onRender3D(com.mojang.blaze3d.vertex.PoseStack matrices, float delta) {
        if (packetPos == null) return;
        float d = BlockUtil.state(packetPos).getDestroyProgress(mc.player, mc.level, packetPos);
        float progress = Mth.clamp((System.currentTimeMillis() - packetStart) / (50f / Math.max(d, 0.0001f)), 0f, 1f);
        int c = ColorUtil.lerp(0xFFFF5C7A, 0xFF5CFF9D, progress);
        AABB b = new AABB(packetPos).deflate(0.5 * (1 - progress));
        Render3D.box(b, ColorUtil.withAlpha(c, 60), c, false);
    }

    @Override public String getInfo() { return mode.get(); }
}
