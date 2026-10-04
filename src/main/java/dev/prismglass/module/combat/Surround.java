package dev.prismglass.module.combat;

import net.minecraft.client.multiplayer.chat.report.Report.Result;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Encases your feet in obsidian. Handles standing across multiple blocks and breaks blocking crystals. */
public class Surround extends Module {
    public final BoolSetting center = bool("Center", true, "Walk to the block centre first (motion, no teleport).");
    public final BoolSetting disableOnJump = bool("DisableOnJump", true, "Turn off when you go up.");
    public final BoolSetting antiCrystal = bool("BreakCrystals", true, "Break crystals blocking a spot.");
    public final BoolSetting support = bool("Support", true, "Place supports under floating spots.");
    private double startY;

    public Surround() { super("Surround", "Surrounds your feet with obsidian.", Category.COMBAT); }

    @Override public void onEnable() { startY = mc.player.getY(); }

    @Override
    public void onTick() {
        if (disableOnJump.get() && mc.player.getY() > startY + 0.5) { toggle(); return; }
        int slot = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ENDER_CHEST);
        if (slot == -1) return;
        if (center.get() && mc.player.onGround()) centerMotion();

        for (BlockPos pos : positions()) {
            if (!BlockUtil.isReplaceable(pos)) continue;
            if (antiCrystal.get()) breakCrystalAt(pos);
            if (support.get() && BlockUtil.getPlaceSide(pos) == null && BlockUtil.canPlace(pos.below())) {
                BlockUtil.place(pos.below(), slot, true, 90);
                continue;
            }
            BlockUtil.Result r = BlockUtil.place(pos, slot, true, 90);
            if (r == BlockUtil.Result.WAITING) return; // rotating toward it; keep order
        }
    }

    private void centerMotion() {
        BlockPos b = mc.player.blockPosition();
        double dx = b.getX() + 0.5 - mc.player.getX(), dz = b.getZ() + 0.5 - mc.player.getZ();
        if (Math.abs(dx) > 0.2 || Math.abs(dz) > 0.2) {
            mc.player.setDeltaMovement(Mth.clamp(dx, -0.2, 0.2), mc.player.getDeltaMovement().y, Mth.clamp(dz, -0.2, 0.2));
        }
    }

    private void breakCrystalAt(BlockPos pos) {
        for (Entity e : mc.level.getEntities(null, new AABB(pos))) {
            if (e instanceof EndCrystal) {
                CombatUtil.face(e, 95);
                CombatUtil.attack(e, false);
                return;
            }
        }
    }

    /** Blocks around every block the player's feet occupy. */
    public static List<BlockPos> positions() {
        AABB box = mc.player.getBoundingBox();
        int y = (int) Math.floor(mc.player.getY() + 0.2);
        Set<BlockPos> feet = new LinkedHashSet<>();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1e-4); x++)
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1e-4); z++)
                feet.add(new BlockPos(x, y, z));
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos f : feet) {
            for (Direction d : BlockUtil.HORIZONTALS) {
                BlockPos n = f.relative(d);
                if (!feet.contains(n) && !out.contains(n)) out.add(n);
            }
        }
        return out;
    }
}
