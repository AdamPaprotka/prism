package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Shoves an enemy with a piston at head height next to them, powered by a redstone torch beside it. The piston head
 * moves into their head space and pushes them one block away from it (pistons move entities with normal collision).
 * Where that side is open they're out of their hole; in a full surround their feet catch on it, so they stay put
 * with the piston head inside their head block instead: suffocation damage, and they can't jump out. The piston
 * faces whoever places it (dominant axis of your rotation, flipped), so it goes on the far side of them from you,
 * the push comes toward you. One action a tick; the torch is broken again afterwards (Retract).
 */
public class PistonPush extends Module {
    public final NumberSetting range = num("Range", 4.5, 2, 6, 0.5, "Target range.");
    public final BoolSetting players = bool("Players", true, "Target players.");
    public final BoolSetting mobs = bool("Mobs", false, "Target mobs too.");
    public final BoolSetting retract = bool("Retract", true, "Break the torch after the push so the piston can fire again.");

    private record Plan(Direction d, BlockPos piston, BlockPos torch) {}

    private LivingEntity target;
    private int firedTicks;
    /** Pushes since enabled (dev checks). */
    public int pushes;

    public PistonPush() { super("PistonPush", "Pushes enemies with a piston (out of holes when the far side is open).", Category.COMBAT); }

    @Override public void onEnable() { pushes = 0; }

    @Override
    public void onTick() {
        target = pickTarget();
        if (target == null) return;
        Hud.setTarget(target);
        Plan p = choose(target);
        if (p == null) return;
        int piston = InvUtil.findHotbar(s -> s.is(Items.PISTON) || s.is(Items.STICKY_PISTON));
        int torch = InvUtil.findHotbar(Items.REDSTONE_TORCH);
        int obsidian = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.COBBLESTONE, Blocks.NETHERRACK);
        if (piston == -1 || torch == -1) return;
        boolean powered = mc.level.getBlockState(p.torch()).is(Blocks.REDSTONE_TORCH);

        if (!PistonUtil.isPiston(p.piston())) {
            if (powered) { PistonUtil.breakInstant(p.torch(), 88); return; }
            // on open ground there's nothing under the spot yet (in a hole it's their surround)
            if (BlockUtil.isReplaceable(p.piston().below())) { if (obsidian != -1) BlockUtil.place(p.piston().below(), obsidian, true, 88); return; }
            if (PistonUtil.facingIfPlaced(p.piston()) == p.d().getOpposite()) BlockUtil.place(p.piston(), piston, true, 88);
            return;
        }
        if (PistonUtil.extended(p.piston())) {
            if (++firedTicks == 1) pushes++;
            if (retract.get() && powered && firedTicks > 4) PistonUtil.breakInstant(p.torch(), 88);
            return;
        }
        firedTicks = 0;
        if (BlockUtil.isReplaceable(p.torch().below())) { if (obsidian != -1) BlockUtil.place(p.torch().below(), obsidian, true, 88); return; }
        if (!powered) BlockUtil.place(p.torch(), torch, true, 88);
    }

    private LivingEntity pickTarget() {
        LivingEntity best = null;
        double bestDist = range.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive()) continue;
            if (e instanceof Player p) {
                if (!players.get() || p.isSpectator() || p.isCreative() || Prism.friends().isFriend(p)) continue;
            } else if (!(mobs.get() && e instanceof Mob)) continue;
            double d = mc.player.distanceTo(e);
            if (d <= bestDist) { bestDist = d; best = le; }
        }
        return best;
    }

    /** Head-level spot beside them on the side away from us, where a piston placed from here faces them. */
    private Plan choose(LivingEntity t) {
        BlockPos head = t.blockPosition().above();
        Vec3 away = t.position().subtract(mc.player.position());
        List<Direction> dirs = new ArrayList<>(List.of(BlockUtil.HORIZONTALS));
        dirs.sort(Comparator.comparingDouble(d -> -(d.getStepX() * away.x + d.getStepZ() * away.z)));
        for (Direction d : dirs) {
            BlockPos piston = head.relative(d);
            boolean ours = PistonUtil.isPiston(piston) && PistonUtil.facing(piston) == d.getOpposite();
            if (!ours) {
                if (!BlockUtil.isReplaceable(piston)) continue;
                BlockPos support = piston.below();
                if (!BlockUtil.isReplaceable(support) || BlockUtil.canPlace(support)) {
                    // it'll be clicked on top of its support: our look there has to run along d so it faces them
                    Vec3 look = Vec3.atCenterOf(support).add(0, 0.5, 0).subtract(mc.player.getEyePosition());
                    if (Direction.getApproximateNearest(look.x, look.y, look.z) != d) continue;
                } else continue;
            }
            if (!BlockUtil.inPlaceRange(piston)) continue;
            for (Direction side : new Direction[]{d.getClockWise(), d.getCounterClockWise()}) {
                BlockPos torch = piston.relative(side);
                var ts = mc.level.getBlockState(torch);
                if ((ts.isAir() || ts.is(Blocks.REDSTONE_TORCH)) && BlockUtil.inPlaceRange(torch)) return new Plan(d, piston, torch);
            }
        }
        return null;
    }

    @Override public String getInfo() { return target == null ? null : target.getName().getString(); }
}
