package dev.prismglass.module.world;

import net.minecraft.client.multiplayer.chat.ChatRestriction.Action;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Set;

/** Breaks blocks around you. Survival mode mines one block at a time with vanilla timing. */
public class Nuker extends Module {
    public final NumberSetting range = num("Range", 4, 1, 6, 0.5, "Break range (capped by AntiCheat place range). Survival mode is Grim-safe: visible face, rotation on block, vanilla speed, one block at a time.");
    public final ModeSetting mode = mode("Mode", "All", "Which blocks.", "All", "Flatten", "List");
    public final ListSetting list = list("Blocks", "netherrack,stone", "Block ids for List mode.", ListSetting.Kind.BLOCK);
    public final BoolSetting instant = bool("CreativeInstant", true, "In creative, break many per tick. [Grim-unsafe: MultiBreak]");
    private BlockPos current;
    private Direction currentFace = Direction.UP;
    private Set<Block> parsed = Set.of();
    private String lastList = "";

    public Nuker() { super("Nuker", "Mines everything around you.", Category.WORLD); }

    @Override public void onDisable() { if (current != null) mc.gameMode.stopDestroyBlock(); current = null; }

    @Override
    public void onTick() {
        if (!list.get().equals(lastList)) {
            lastList = list.get();
            Set<Block> s = new HashSet<>();
            for (String id : lastList.split(",")) {
                Identifier i = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
                if (i != null && BuiltInRegistries.BLOCK.containsKey(i)) s.add(BuiltInRegistries.BLOCK.getValue(i));
            }
            parsed = s;
        }
        double r = Prism.anticheat().placeRange(range.get());
        if (mc.player.isCreative() && instant.get()) {
            int n = 0;
            for (BlockPos pos : BlockPos.betweenClosed(mc.player.blockPosition().offset((int) -r, (int) -r, (int) -r), mc.player.blockPosition().offset((int) r, (int) r, (int) r))) {
                if (!valid(pos, r)) continue;
                mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos.immutable(), Direction.UP));
                if (++n >= 20) break;
            }
            return;
        }
        if (current == null || !valid(current, r)) {
            current = null;
            double best = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(mc.player.blockPosition().offset((int) -r, (int) -r, (int) -r), mc.player.blockPosition().offset((int) r, (int) r, (int) r))) {
                if (!valid(pos, r) || BlockUtil.breakFace(pos) == null) continue;
                double d = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
                if (d < best) { best = d; current = pos.immutable(); }
            }
            if (current != null) currentFace = BlockUtil.breakFace(current); // keep one face per block (WrongBreak/PositionBreakB)
        }
        if (current == null) return;
        // Grim RotationBreak: dig only once the rotation the server has is actually on the block
        if (!BlockUtil.readyToDig(current, 30)) return;
        mc.gameMode.continueDestroyBlock(current, currentFace);
        mc.player.swing(InteractionHand.MAIN_HAND); // NoSwingBreak
    }

    private boolean valid(BlockPos pos, double r) {
        if (BlockUtil.isReplaceable(pos) || BlockUtil.isUnbreakable(pos)) return false;
        if (RotationUtil.distanceToBox(mc.player.getEyePosition(), new AABB(pos)) > r) return false;
        return switch (mode.get()) {
            case "Flatten" -> pos.getY() >= mc.player.getBlockY();
            case "List" -> parsed.contains(BlockUtil.state(pos).getBlock());
            default -> true;
        };
    }
}
