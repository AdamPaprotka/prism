package dev.prismglass.module.combat;

import com.mojang.blaze3d.vertex.PoseStack;
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
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Piston crystal aura: pushes an end crystal into the enemy's head space before blowing it up, so nothing between
 * the explosion and them takes any damage off (a face-placed crystal at head height loses part of it to the
 * surround). Layout on the side of the target facing away from you, with d = that direction:
 * <pre>
 *   head level:   [target head] [C crystal]  [P piston, facing back at them]  [R torch beside P]
 *   feet level:   [target]      [B surround] [S support]                      [support under R]
 * </pre>
 * Pistons face the player who places them: the dominant axis of the rotation the server last got, flipped. Placing
 * P from your side of the target means looking along d, so P faces back at them (checked before committing).
 * One action a tick: supports, piston, crystal on B, torch (the piston fires and shoves the crystal one block into
 * their head space), hit the crystal, break the torch so the piston pulls back, again. Pistons push entities with
 * normal collision (PistonMovingBlockEntity.moveCollidedEntities), so the crystal stops at their head if anything
 * is in the way. Every hit also counts the self-damage like CrystalAura's AntiSuicide.
 */
public class PistonAura extends Module {
    public final NumberSetting range = num("Range", 4.5, 2, 6, 0.5, "Target range.");
    public final BoolSetting players = bool("Players", true, "Target players.");
    public final BoolSetting mobs = bool("Mobs", false, "Target mobs too.");
    public final NumberSetting minDamage = num("MinDamage", 6, 0, 36, 0.5, "Only set up when the pushed crystal does at least this much.");
    public final NumberSetting maxSelf = num("MaxSelfDamage", 8, 0, 36, 0.5, "Never hit a crystal that does more than this to you.");
    public final BoolSetting antiSuicide = bool("AntiSuicide", true, "Never hit a crystal that would kill you.");
    public final BoolSetting render = bool("Render", true, "Show the piston, crystal and torch spots.");

    /** One layout around a target. */
    private record Plan(BlockPos head, Direction d, BlockPos base, BlockPos crystal, BlockPos piston, BlockPos support, BlockPos torch) {}

    private LivingEntity target;
    private Plan plan;
    private int extendedTicks;
    /** Crystals hit since enabled (dev checks). */
    public int detonations;

    public PistonAura() { super("PistonAura", "Pushes crystals into enemies with pistons for full damage.", Category.COMBAT); }

    @Override public void onEnable() { detonations = 0; }
    @Override public void onDisable() { target = null; plan = null; }

    @Override
    public void onTick() {
        target = pickTarget();
        if (target == null) { plan = null; return; }
        Hud.setTarget(target);
        plan = choose(target);
        if (plan == null) return;
        Plan p = plan;

        int obsidian = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
        int piston = InvUtil.findHotbar(s -> s.is(Items.PISTON) || s.is(Items.STICKY_PISTON));
        int torch = InvUtil.findHotbar(Items.REDSTONE_TORCH);
        int crystal = InvUtil.findHotbar(Items.END_CRYSTAL);
        if (piston == -1 || torch == -1 || crystal == -1) return;

        // 1) the piston, on its support
        if (!PistonUtil.isPiston(p.piston())) {
            if (BlockUtil.isReplaceable(p.support())) { if (obsidian != -1) BlockUtil.place(p.support(), obsidian, true, 90); return; }
            if (mc.level.getBlockState(p.torch()).is(Blocks.REDSTONE_TORCH)) { PistonUtil.breakInstant(p.torch(), 90); return; } // it would fire at once
            if (PistonUtil.facingIfPlaced(p.piston()) == p.d().getOpposite()) BlockUtil.place(p.piston(), piston, true, 90);
            return;
        }
        if (PistonUtil.facing(p.piston()) != p.d().getOpposite()) return; // someone else's piston: choose() skips this side next tick
        // 2) something for the torch to stand on
        if (BlockUtil.isReplaceable(p.torch().below())) { if (obsidian != -1) BlockUtil.place(p.torch().below(), obsidian, true, 90); return; }

        EndCrystal c = crystalNear(p);
        boolean powered = mc.level.getBlockState(p.torch()).is(Blocks.REDSTONE_TORCH);
        if (PistonUtil.extended(p.piston())) {
            extendedTicks++;
            if (c != null) {
                // give the push its two ticks, then blow it up
                if (extendedTicks < 2 || !safe(c.position())) return;
                CombatUtil.face(c, 92);
                if (CombatUtil.attack(c, false)) detonations++;
                return;
            }
            if (powered) PistonUtil.breakInstant(p.torch(), 90); // pull the piston back for the next crystal
            return;
        }
        extendedTicks = 0;
        // 3) a crystal in front of the piston, then 4) power it
        if (c == null) {
            if (CombatUtil.canPlaceCrystal(p.base(), false)) BlockUtil.click(p.base(), Direction.UP, crystal, true, 90);
            return;
        }
        if (!powered) BlockUtil.place(p.torch(), torch, true, 90);
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

    /** The best side for the layout: the side facing away from us first (so the piston faces them). */
    private Plan choose(LivingEntity t) {
        BlockPos feet = t.blockPosition(), head = feet.above();
        if (!mc.level.getBlockState(head).isAir()) return null;
        Vec3 away = t.position().subtract(mc.player.position());
        List<Direction> dirs = new ArrayList<>(List.of(BlockUtil.HORIZONTALS));
        dirs.sort(Comparator.comparingDouble(d -> -(d.getStepX() * away.x + d.getStepZ() * away.z)));
        Vec3 blast = new Vec3(head.getX() + 0.5, head.getY(), head.getZ() + 0.5);
        if (DamageUtil.crystalDamage(t, blast) < minDamage.get() || !safe(blast)) return null;
        for (Direction d : dirs) {
            BlockPos base = feet.relative(d), crystal = base.above(), piston = crystal.relative(d), support = piston.below();
            var bs = mc.level.getBlockState(base);
            if (!bs.is(Blocks.OBSIDIAN) && !bs.is(Blocks.BEDROCK)) continue;
            boolean ours = PistonUtil.isPiston(piston) && PistonUtil.facing(piston) == d.getOpposite();
            var cs = mc.level.getBlockState(crystal);
            // once our piston fired its head stands where the crystal was: still our layout
            if (!cs.isAir() && !(ours && (cs.is(Blocks.PISTON_HEAD) || cs.is(Blocks.MOVING_PISTON)))) continue;
            if (!ours && !BlockUtil.isReplaceable(piston)) continue;
            if (!BlockUtil.inPlaceRange(piston) || !BlockUtil.inPlaceRange(base)) continue;
            if (!ours) {
                // placed from here the piston must face back at the target: our look to its support has to run along d
                Vec3 look = Vec3.atCenterOf(support).add(0, 0.5, 0).subtract(mc.player.getEyePosition());
                if (Direction.getApproximateNearest(look.x, look.y, look.z) != d) continue;
            }
            for (Direction side : new Direction[]{d.getClockWise(), d.getCounterClockWise()}) {
                BlockPos torch = piston.relative(side);
                var ts = mc.level.getBlockState(torch);
                if (!ts.isAir() && !ts.is(Blocks.REDSTONE_TORCH)) continue;
                if (!BlockUtil.inPlaceRange(torch)) continue;
                return new Plan(head, d, base, crystal, piston, support, torch);
            }
        }
        return null;
    }

    /** A crystal in front of the piston or already pushed into their head space. */
    private EndCrystal crystalNear(Plan p) {
        AABB area = new AABB(p.crystal()).minmax(new AABB(p.head())).inflate(0.3);
        for (Entity e : mc.level.getEntities(null, area)) if (e instanceof EndCrystal c && c.isAlive()) return c;
        return null;
    }

    private boolean safe(Vec3 at) {
        float self = DamageUtil.crystalDamage(mc.player, at);
        if (self > maxSelf.get()) return false;
        return !antiSuicide.get() || self < DamageUtil.health(mc.player) - 1f;
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        Plan p = plan;
        if (!render.get() || p == null) return;
        Render3D.box(new AABB(p.piston()), 0x30FFB84D, 0xC0FFB84D, false);
        Render3D.box(new AABB(p.crystal()), 0x30FF4D8A, 0xC0FF4D8A, false);
        Render3D.box(new AABB(p.torch()).deflate(0.3), 0x40FF2020, 0xC0FF2020, false);
    }

    @Override public String getInfo() { return target == null ? null : target.getName().getString(); }
}
