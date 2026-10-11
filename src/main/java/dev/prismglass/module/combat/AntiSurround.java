package dev.prismglass.module.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Stops enemies from (re)building their surround: an end crystal standing in a surround slot is an entity in the
 * way, so the server refuses their block there. Place one in every open slot next to their feet, or only in slots
 * whose block just got broken (AutoCity / PacketMine combo), and CrystalAura gets a crystal right against them.
 * Only slots whose floor is obsidian or bedrock can take a crystal.
 */
public class AntiSurround extends Module {
    public final ModeSetting mode = mode("Mode", "Broken", "Broken: only slots whose block was just mined out. Gaps: every open slot.", "Broken", "Gaps");
    public final NumberSetting targetRange = num("TargetRange", 6, 2, 10, 0.5, "Enemy range.");
    public final NumberSetting placeRange = num("PlaceRange", 4.5, 1, 6, 0.1, "Place range (capped by AntiCheat).");
    public final NumberSetting memory = num("Memory", 40, 5, 200, 5, "Broken mode: how many ticks a mined slot counts as freshly broken.");
    public final NumberSetting maxSelf = num("MaxSelfDamage", 8, 0, 36, 0.5, "Skip slots whose crystal would hit you harder than this.");
    public final BoolSetting onlyMoving = bool("SkipIfFleeing", true, "Don't bother when the enemy is running away from their hole.");
    public final BoolSetting mobs = bool("Mobs", false, "Target hostile mobs too (practice).");
    public final ModeSetting swap = mode("Swap", "Normal", "How to get crystals in hand.", "Normal", "None");
    public final ColorSetting color = color("Color", 0xFFB197FC, "Render colour.");

    /** Surround slot -> tick it was last seen holding a block. */
    private final Map<BlockPos, Integer> wasBlock = new HashMap<>();
    private BlockPos rendered;
    private int ticks, renderTicks;

    public AntiSurround() { super("AntiSurround", "Puts crystals in enemies' surround slots so they can't block them.", Category.COMBAT); }

    @Override public void onDisable() { wasBlock.clear(); rendered = null; }

    @Override
    public void onTick() {
        ticks++;
        if (renderTicks > 0 && --renderTicks == 0) rendered = null;
        List<LivingEntity> ts = EntityUtil.targets(targetRange.get(),
            new EntityUtil.TargetFilter(true, mobs.get(), false, true, true), "Distance");
        if (ts.isEmpty()) { wasBlock.clear(); return; }
        LivingEntity t = ts.get(0);
        if (onlyMoving.get() && t.getDeltaMovement().horizontalDistanceSqr() > 0.04) return;

        BlockPos feet = t.blockPosition();
        double range = Prism.anticheat().placeRange(placeRange.get());
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : BlockUtil.HORIZONTALS) {
            BlockPos slot = feet.relative(d);
            if (!BlockUtil.isReplaceable(slot)) { wasBlock.put(slot.immutable(), ticks); continue; }
            if (mode.is("Broken")) {
                Integer seen = wasBlock.get(slot);
                if (seen == null || ticks - seen > memory.getInt()) continue;
            }
            BlockPos base = slot.below();
            if (!CombatUtil.canPlaceCrystal(base, false)) continue;
            if (RotationUtil.distanceToBox(eye, new AABB(base)) > range) continue;
            if (DamageUtil.crystalDamage(mc.player, CombatUtil.crystalPos(base)) > maxSelf.get()) continue;
            double dist = eye.distanceToSqr(Vec3.atCenterOf(base));
            if (dist < bestDist) { bestDist = dist; best = base; }
        }
        wasBlock.keySet().removeIf(p -> p.distManhattan(feet) > 2);
        if (best == null) return;
        Hud.setTarget(t);
        place(best, range);
    }

    private void place(BlockPos base, double range) {
        InteractionHand hand = CombatUtil.crystalHand();
        int slot = -1, prev = mc.player.getInventory().getSelectedSlot();
        if (hand == null) {
            if (swap.is("None")) return;
            slot = InvUtil.findHotbar(Items.END_CRYSTAL);
            if (slot == -1 || !Prism.guard().canSwitchSlot()) return;
            hand = InteractionHand.MAIN_HAND;
        }
        Vec3 eye = mc.player.getEyePosition();
        Direction face = Direction.UP;
        if (Prism.anticheat().strictDirection.get() && !BlockUtil.isFaceVisible(base, Direction.UP, eye)) {
            face = null;
            for (Direction d : Direction.values()) if (BlockUtil.isFaceVisible(base, d, eye)) { face = d; break; }
            if (face == null) return;
        }
        Vec3 hitVec = Vec3.atCenterOf(base).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        float[] rot = RotationUtil.toward(hitVec);
        if (Prism.anticheat().placeRotate.is("Silent")) {
            Prism.rotations().request(rot[0], rot[1], 105);
            if (!Prism.rotations().isFacing(new AABB(base), range)) return;
        } else if (Prism.anticheat().placeRotate.is("Packet")) {
            Prism.rotations().sendLook(rot[0], rot[1]);
        }
        if (!Prism.guard().canPlace()) return;
        if (slot != -1) InvUtil.swap(slot, false);
        mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(hitVec, face, base, false));
        mc.player.swing(hand);
        if (slot != -1) InvUtil.restore(prev);
        rendered = base;
        renderTicks = 10;
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        if (rendered != null) Render3D.box(new AABB(rendered.above()), ColorUtil.withAlpha(color.color(), 50), color.color(), false);
    }

    @Override public String getInfo() { return mode.get(); }
}
