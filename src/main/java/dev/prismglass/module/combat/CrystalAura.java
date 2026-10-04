package dev.prismglass.module.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.AntiCheat;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crystal PvP automation, v2.
 *
 * <ul>
 *   <li>Damage is vanilla's exact explosion math (DamageUtil) evaluated where each target <i>will</i> be: its current
 *       velocity extrapolated {@code Extrapolate} ticks (≈ your ping), with exposure raycasts against that box.</li>
 *   <li>Spots are scored {@code targetDamage - selfDamage * Balance}; a spot that kills is always taken first. Spots that
 *       can't reach MinDamage even at full exposure are skipped before any raycast, which keeps it fast.</li>
 *   <li>Several targets (closest 3) are considered; the best spot over all of them wins.</li>
 *   <li>Grim-safe order: place, then break, in one tick (PacketOrderJ); silent rotations through RotationManager;
 *       a crystal isn't attacked again within the Inhibit window (no duplicate attack packets).</li>
 * </ul>
 */
public class CrystalAura extends Module {
    public final BoolSetting players = bool("Players", true, "Target players.");
    public final BoolSetting mobs = bool("Mobs", false, "Target hostile mobs too.");
    public final NumberSetting targetRange = num("TargetRange", 10, 4, 16, 0.5, "Enemy search range.");
    public final NumberSetting placeRange = num("PlaceRange", 4.5, 1, 6, 0.1, "Place range (capped by AntiCheat).");
    public final NumberSetting breakRange = num("BreakRange", 4.5, 1, 6, 0.1, "Break range (capped by AntiCheat attack range).");
    public final NumberSetting minDamage = num("MinDamage", 6, 0, 36, 0.5, "Minimum damage to the target.");
    public final NumberSetting maxSelf = num("MaxSelfDamage", 8, 0, 36, 0.5, "Maximum damage to yourself.");
    public final NumberSetting balance = num("Balance", 0.5, 0, 2, 0.05, "How much your own damage counts against a spot.");
    public final BoolSetting antiSuicide = bool("AntiSuicide", true, "Never place/break what would kill you.");
    public final NumberSetting facePlace = num("FacePlaceHP", 8, 0, 36, 0.5, "Face-place when target is at or below this health.");
    public final NumberSetting armorBreak = num("ArmorBreak%", 15, 0, 100, 1, "Face-place when a target armour piece is below this durability (0 = off).");
    public final NumberSetting extrapolate = num("Extrapolate", 2, 0, 8, 1, "Ticks of target movement to predict (about your ping / 50ms).");
    public final BoolSetting breakableOnly = bool("BreakableOnly", true,
        "Only place crystals you can also break (within your attack range; Grim caps it at 3.0).");
    public final NumberSetting placeDelay = num("PlaceDelay", 0, 0, 10, 1, "Ticks between places.");
    public final NumberSetting breakDelay = num("BreakDelay", 0, 0, 10, 1, "Ticks between breaks.");
    public final NumberSetting inhibit = num("Inhibit", 4, 0, 20, 1, "Ticks before the same crystal may be attacked again.");
    public final ModeSetting swap = mode("Swap", "Normal", "How to get crystals in hand.", "Normal", "Silent", "None");
    public final BoolSetting antiWeakness = bool("AntiWeakness", true, "With Weakness, hit crystals with your best weapon (swapped back after).");
    public final BoolSetting instantBreak = bool("PredictBreak", false, "Break crystals the tick they spawn. [Grim: may flag MultiInteract]");
    public final BoolSetting showDamage = bool("ShowDamage", true, "Damage number on the placement.");
    public final ColorSetting color = color("Color", 0xFFB197FC, "Render colour.");

    private LivingEntity target;
    private BlockPos renderPos;
    private float renderDamage, renderSelf;
    private int placeTicks, breakTicks, ticks;
    private boolean busy;
    private final Map<Integer, Integer> attacked = new HashMap<>(); // crystal id -> tick
    private double[] label;
    private int dbgNotFacing, dbgPlaces;
    private float[] dbgWant = {0, 0};

    public CrystalAura() { super("CrystalAura", "Places and breaks crystals at enemies (predicts movement, exact damage).", Category.COMBAT); }

    public boolean isBusy() { return busy; }

    @Override public void onDisable() { target = null; renderPos = null; busy = false; attacked.clear(); }

    @Override
    public void onTick() {
        busy = false;
        ticks++;
        placeTicks++;
        breakTicks++;
        attacked.values().removeIf(t -> ticks - t > 40);
        List<LivingEntity> targets = EntityUtil.targets(targetRange.get(),
            new EntityUtil.TargetFilter(players.get(), mobs.get(), false, true, true), "Distance");
        if (targets.size() > 3) targets = targets.subList(0, 3);
        if (targets.isEmpty()) { target = null; renderPos = null; return; }
        if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return;
        doPlace(targets);
        doBreak(targets);
        if (target != null) Hud.setTarget(target);
    }

    /** Where a target will be in Extrapolate ticks (stops at walls/floor). */
    private Vec3 predicted(LivingEntity t) {
        int n = extrapolate.getInt();
        if (n == 0) return t.position();
        Vec3 v = t.getDeltaMovement();
        Vec3 delta = new Vec3(v.x * n, t.onGround() ? 0 : v.y * n, v.z * n);
        AABB moved = t.getBoundingBox().move(delta);
        return mc.level.noCollision(t, moved) ? t.position().add(delta) : t.position();
    }

    private float minDamageFor(LivingEntity t) {
        if (DamageUtil.health(t) <= facePlace.get()) return 1.5f;
        if (armorBreak.get() > 0) {
            for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                var s = t.getItemBySlot(slot);
                if (s.isDamageableItem() && (s.getMaxDamage() - s.getDamageValue()) * 100f / s.getMaxDamage() < armorBreak.get()) return 1.5f;
            }
        }
        return minDamage.getFloat();
    }

    private boolean selfOk(float self) {
        if (self > maxSelf.get()) return false;
        return !antiSuicide.get() || self < DamageUtil.health(mc.player) - 1f;
    }

    /** Best target and score for a crystal at {@code c}; null if no target clears its minimum. */
    private Object[] evaluate(List<LivingEntity> targets, List<Vec3> feet, Vec3 c) {
        float self = -1;
        LivingEntity bestT = null;
        float bestScore = Float.NEGATIVE_INFINITY, bestDmg = 0;
        for (int i = 0; i < targets.size(); i++) {
            LivingEntity t = targets.get(i);
            float min = minDamageFor(t);
            if (DamageUtil.crystalDamageMax(t, c, feet.get(i)) < min) continue; // can't reach it even fully exposed
            float dmg = DamageUtil.crystalDamageAt(t, c, feet.get(i));
            if (dmg < min) continue;
            if (self < 0) {
                self = DamageUtil.crystalDamage(mc.player, c);
                if (!selfOk(self)) return null;
            }
            float score = dmg - self * balance.getFloat() + (dmg >= DamageUtil.health(t) ? 100f : 0f);
            if (score > bestScore) { bestScore = score; bestT = t; bestDmg = dmg; }
        }
        return bestT == null ? null : new Object[]{bestT, bestScore, bestDmg, self};
    }

    // ---- place --------------------------------------------------------------------------------

    private void doPlace(List<LivingEntity> targets) {
        if (placeTicks < placeDelay.getInt()) return;
        AntiCheat ac = Prism.anticheat();
        double range = ac.placeRange(placeRange.get());
        double hitRange = ac.range(breakRange.get());
        Vec3 eye = mc.player.getEyePosition();
        List<Vec3> feet = new ArrayList<>();
        for (LivingEntity t : targets) feet.add(predicted(t));

        BlockPos best = null;
        Object[] bestEval = null;
        int r = (int) Math.ceil(range);
        BlockPos me = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(me.offset(-r, -r, -r), me.offset(r, r, r))) {
            if (RotationUtil.distanceToBox(eye, new AABB(pos)) > range) continue;
            if (!CombatUtil.canPlaceCrystal(pos, false)) continue;
            Vec3 c = CombatUtil.crystalPos(pos);
            if (breakableOnly.get() && RotationUtil.distanceToBox(eye, new AABB(c.x - 1, c.y, c.z - 1, c.x + 1, c.y + 2, c.z + 1)) > hitRange) continue;
            Object[] ev = evaluate(targets, feet, c);
            if (ev == null || (bestEval != null && (float) ev[1] <= (float) bestEval[1])) continue;
            best = pos.immutable();
            bestEval = ev;
        }
        renderPos = best;
        if (best == null) { target = targets.get(0); return; }
        target = (LivingEntity) bestEval[0];
        renderDamage = (float) bestEval[2];
        renderSelf = (float) bestEval[3];
        busy = true;

        InteractionHand hand = CombatUtil.crystalHand();
        int slot = -1, prev = mc.player.getInventory().getSelectedSlot();
        if (hand == null) {
            if (swap.is("None")) return;
            slot = InvUtil.findHotbar(Items.END_CRYSTAL);
            if (slot == -1 || !Prism.guard().canSwitchSlot()) return;
            hand = InteractionHand.MAIN_HAND;
        }

        // a visible face of the base (UP if we're above it, otherwise a side)
        Direction face = Direction.UP;
        if (ac.strictDirection.get() && !BlockUtil.isFaceVisible(best, Direction.UP, eye)) {
            face = null;
            for (Direction d : Direction.values()) if (BlockUtil.isFaceVisible(best, d, eye)) { face = d; break; }
            if (face == null) return;
        }
        Vec3 hitVec = Vec3.atCenterOf(best).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        float[] rot = RotationUtil.toward(hitVec);
        dbgWant = rot;
        if (ac.placeRotate.is("Silent")) {
            Prism.rotations().request(rot[0], rot[1], 110);
            if (!Prism.rotations().isFacing(new AABB(best), range)) { dbgNotFacing++; return; }
        } else if (ac.placeRotate.is("Packet")) {
            Prism.rotations().sendLook(rot[0], rot[1]);
        }
        if (!Prism.guard().canPlace()) return;

        if (slot != -1) InvUtil.swap(slot, false);
        dbgPlaces++;
        mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(hitVec, face, best, false));
        mc.player.swing(hand);
        if (slot != -1 && !swap.is("Normal")) InvUtil.restore(prev);
        placeTicks = 0;
    }

    // ---- break --------------------------------------------------------------------------------

    private void doBreak(List<LivingEntity> targets) {
        if (breakTicks < breakDelay.getInt()) return;
        double range = Prism.anticheat().range(breakRange.get());
        List<Vec3> feet = new ArrayList<>();
        for (LivingEntity t : targets) feet.add(predicted(t));
        EndCrystal best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EndCrystal crystal) || !e.isAlive()) continue;
            Integer last = attacked.get(e.getId());
            if (last != null && ticks - last < inhibit.getInt()) continue;
            if (RotationUtil.distanceTo(e) > range) continue;
            Object[] ev = evaluate(targets, feet, e.position());
            if (ev == null) continue;
            float score = (float) ev[1];
            if (score > bestScore) { bestScore = score; best = crystal; }
        }
        if (best == null) return;
        busy = true;
        CombatUtil.face(best, 120);
        if (hitCrystal(best)) {
            breakTicks = 0;
            attacked.put(best.getId(), ticks);
        }
    }

    /** Attack a crystal; with Weakness (and no Strength) swap to a weapon for the hit, then back. */
    private boolean hitCrystal(Entity crystal) {
        boolean weak = antiWeakness.get() && mc.player.hasEffect(MobEffects.WEAKNESS) && !mc.player.hasEffect(MobEffects.STRENGTH);
        int prev = mc.player.getInventory().getSelectedSlot();
        int weapon = weak ? CombatUtil.bestWeapon(false) : -1;
        if (weapon != -1 && weapon != prev) {
            if (!Prism.guard().canSwitchSlot()) return false;
            InvUtil.swap(weapon, false);
        }
        boolean hit = CombatUtil.attack(crystal, false);
        if (weapon != -1 && weapon != prev) InvUtil.restore(prev);
        return hit;
    }

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (!instantBreak.get() || !(event.packet instanceof ClientboundAddEntityPacket spawn) || spawn.getType() != EntityType.END_CRYSTAL) return;
        mc.execute(() -> {
            Entity e = mc.level == null ? null : mc.level.getEntity(spawn.getId());
            if (e != null && target != null && RotationUtil.distanceTo(e) <= Prism.anticheat().range(breakRange.get()) && hitCrystal(e)) {
                attacked.put(e.getId(), ticks);
            }
        });
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        label = null;
        if (renderPos == null) return;
        int c = color.color();
        Render3D.box(new AABB(renderPos), ColorUtil.withAlpha(c, 50), c, false);
        if (showDamage.get()) label = Render3D.project(Vec3.atCenterOf(renderPos).add(0, 0.9, 0));
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (label == null || renderPos == null) return;
        String text = String.format("%.1f §7/ %.1f", renderDamage, renderSelf);
        float w = mc.font.width(text) + 10;
        float x = (float) label[0] - w / 2, y = (float) label[1] - 7;
        Glass.panel(ctx, x, y, w, 12, Prism.modules().get(ClickGui.class).panelStyle());
        ctx.text(mc.font, text, (int) x + 5, (int) y + 2, 0xFFFFFFFF, true);
    }

    /** Dev: what the aura is doing. */
    public String debugState() {
        return "target=" + (target == null ? null : target.getName().getString()) + " spot=" + renderPos + " dmg=" + renderDamage + " self=" + renderSelf
            + " busy=" + busy + " hand=" + CombatUtil.crystalHand() + " canPlace=" + Prism.guard().canPlace()
            + " rotate=" + Prism.anticheat().placeRotate.get() + " notFacing=" + dbgNotFacing + " places=" + dbgPlaces
            + " hitRange=" + Prism.anticheat().range(breakRange.get()) + " preset=" + Prism.anticheat().preset.get()
            + " serverRot=" + Prism.rotations().isActive()
            + String.format(" want=%.1f/%.1f server=%.1f/%.1f cam=%.1f/%.1f", dbgWant[0], dbgWant[1], Prism.rotations().getServerYaw(), Prism.rotations().getServerPitch(), mc.player.getYRot(), mc.player.getXRot());
    }

    @Override public String getInfo() { return target == null ? null : target.getName().getString() + " " + String.format("%.1f", renderDamage); }
}
