package dev.prismglass.module.combat;

import net.minecraft.client.multiplayer.chat.ChatRestriction.Action;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.ItemTags;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Packet-mines the enemy's surround block that opens a crystal spot ("citying"). */
public class AutoCity extends Module {
    public final NumberSetting targetRange = num("TargetRange", 5, 2, 8, 0.5, "Enemy range.");
    public final BoolSetting autoTool = bool("AutoTool", true, "Switch to your best pickaxe.");
    private BlockPos mining;
    private Direction miningFace = Direction.UP;
    private long startedAt;

    public AutoCity() { super("AutoCity", "Breaks enemy surround blocks.", Category.COMBAT); }

    @Override public void onDisable() { mining = null; }

    @Override
    public void onTick() {
        if (mining != null) {
            if (BlockUtil.isReplaceable(mining)) { mining = null; return; }
            BlockState s = BlockUtil.state(mining);
            float delta = s.getDestroyProgress(mc.player, mc.level, mining);
            long needed = (long) (50 / Math.max(delta, 0.001f));
            if (System.currentTimeMillis() - startedAt >= needed && Prism.guard().canPlace()) {
                Direction face = BlockUtil.breakFace(mining);
                if (face == null || !BlockUtil.readyToDig(mining, 90)) return;
                mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, mining, face));
                mc.player.swing(InteractionHand.MAIN_HAND);
                startedAt = System.currentTimeMillis() + 2000; // give the server time; retry if it's still there
            }
            return;
        }
        Player t = EntityUtil.closestEnemy(targetRange.get());
        if (t == null || !EntityUtil.isInHole(t)) return;
        Hud.setTarget(t);
        for (Direction d : BlockUtil.HORIZONTALS) {
            BlockPos block = t.blockPosition().relative(d);
            if (BlockUtil.isUnbreakable(block) || !BlockUtil.inPlaceRange(block)) continue;
            // after breaking, a crystal goes on the block under it (needs obsidian/bedrock there)
            BlockState under = BlockUtil.state(block.below());
            if (!under.is(net.minecraft.world.level.block.Blocks.OBSIDIAN) && !under.is(net.minecraft.world.level.block.Blocks.BEDROCK)) continue;
            if (autoTool.get()) {
                int pick = InvUtil.findHotbar(st -> st.is(net.minecraft.tags.ItemTags.PICKAXES));
                if (pick != -1 && Prism.guard().canSwitchSlot()) InvUtil.swap(pick, false);
            }
            Direction face = BlockUtil.breakFace(block);
            if (face == null) continue;
            if (!BlockUtil.readyToDig(block, 90)) return; // wait for the rotation before starting
            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, block, face));
            mc.player.swing(InteractionHand.MAIN_HAND);
            mining = block;
            miningFace = face;
            startedAt = System.currentTimeMillis();
            return;
        }
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        if (mining != null) Render3D.box(new AABB(mining), 0x40FF5C7A, 0xFFFF5C7A, false);
    }
}
