package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.manager.MovementHooks;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Mace smashes. A mace hit after falling more than 1.5 blocks gets a bonus that grows with the fall (4/block for the
 * first 3, 2/block up to 8, then 1/block, plus Density), and the server adds it AFTER scaling the hit by attack
 * charge: so the bonus is always full, and a fully charged hit is also a crit (x1.5 on the whole thing).
 *
 * <p>The charge: the server applies a newly held item's attack speed/damage only in its next entity tick, after it has
 * handled our packets. So swapping to the mace in the hit's own tick is judged with the item held before (a sword: full
 * in 12.5 ticks, 7 base damage) plus the mace's smash bonus. Holding the mace would need its own ~33 ticks (measured:
 * 23 vs 38 damage). Firework rockets reset the charge too (26.3: any item use that swings). Hence:
 * <ul>
 *   <li>AutoHit smashes at the LAST tick it can: right before you land or fall out of reach (every block counts),
 *       swapping to the mace for that hit only;</li>
 *   <li>WindJump: with an enemy close, jump and throw a wind charge straight down to launch yourself;</li>
 *   <li>Spam (BETA): elytra loop. Rocket up, swap to a chestplate over the target (gliding blocks the smash), smash,
 *       elytra back on, repeat. Right click a player to pick the target.</li>
 * </ul>
 * Fall distance is tracked here (the client never gets the server's): highest point since you last went up.
 */
public class AutoMace extends Module {
    public final BoolSetting windJump = bool("WindJump", true, "Launch yourself with a wind charge when an enemy is close.");
    public final NumberSetting range = num("Range", 4, 2, 8, 0.5, "WindJump: launch when an enemy is this close.");
    public final BoolSetting autoHit = bool("AutoHit", true, "Smash the enemy below you at the last tick before landing (the further you fell, the harder).");
    public final NumberSetting minFall = num("MinFall", 2.5, 1.6, 12, 0.5, "AutoHit: never smash before you've fallen this far.");
    public final BoolSetting mobs = bool("Mobs", false, "Also target hostile mobs.");
    public final BoolSetting spam = bool("Spam", false,
        "BETA. Elytra loop: rocket up, swap to a chestplate over the target, smash, elytra back on, repeat. Right click a player to pick the target. Rockets in the offhand keep the mace charged.");
    public final NumberSetting height = num("Height", 24, 8, 60, 1, "Spam: how high above the target to climb before dropping.").visibleWhen(() -> spam.get());
    public final BoolSetting autoTarget = bool("AutoTarget", true, "Spam: with no picked target, go for the nearest enemy.").visibleWhen(() -> spam.get());
    public final NumberSetting spamRange = num("SpamRange", 40, 8, 128, 1, "Spam: AutoTarget search range.").visibleWhen(() -> spam.get() && autoTarget.get());

    private double apexY;
    private int jumpStage, cooldown, airTicks, heldBefore = -1;
    private LivingEntity launchTarget;

    // ---- Spam
    private enum State { IDLE, TAKEOFF, CLIMB, POSITION, DROP, RECOVER }

    private State state = State.IDLE;
    private LivingEntity picked, spamTarget;
    private boolean rightWasDown, chestSwapped;
    private int stateTicks, ticks, lastRocket = -1000, rocketRestore = -1, smashes, movingTicks, exitTicks;
    private String missing;

    public AutoMace() { super("AutoMace", "Mace smashes at the last tick of the fall; Spam = elytra smash loop (BETA).", Category.COMBAT); }

    /**
     * Spam switched off (or the module) mid-loop: never leave someone falling 30 blocks in a chestplate. Put the
     * elytra back on and open it (MovementHooks does the jump taps even with the module off).
     */
    private void safeExit() {
        var p = mc.player;
        if (p == null || p.onGround() || p.isFallFlying() || p.isInWater()) { exitTicks = 0; return; }
        if (!wearingGlider()) { equip(true); return; } // waits a tick when a click isn't allowed yet
        if (!MovementHooks.glidePending()) MovementHooks.requestGlide();
    }

    @Override
    public void onDisable() {
        if (state != State.IDLE) safeExit();
        state = State.IDLE;
    }

    @Override
    public void onEnable() {
        state = State.IDLE;
        jumpStage = airTicks = 0;
        heldBefore = -1;
        missing = null;
        if (mc.player != null) apexY = mc.player.getY();
    }

    /** How far we've fallen since the top of the arc. */
    public double fall() {
        var p = mc.player;
        return p.onGround() || p.isFallFlying() || p.isInWater() ? 0 : Math.max(0, apexY - p.getY());
    }

    public boolean canSmash() { return fall() > 1.5 && maceSlot() != -1; }

    /** Smashes landed by Spam since it was switched on (dev/HUD). */
    public int smashes() { return smashes; }

    public String state() { return state.name(); }

    /** Dev: last drop prediction (horizontal miss, blocks to the ground) and the last smash check. */
    public String debugDrop = "", debugSmash = "";

    private int maceSlot() {
        return WeaponSwap.best(s -> s.is(Items.MACE), s -> 1 + WeaponSwap.enchantLevel(s, Enchantments.DENSITY) * 2 + WeaponSwap.enchantLevel(s, Enchantments.BREACH));
    }

    private boolean isEnemy(Entity e) {
        if (!(e instanceof LivingEntity le) || e == mc.player || !le.isAlive()) return false;
        return e instanceof Player pl ? !Prism.friends().isFriend(pl) && !pl.isSpectator() : mobs.get() && e instanceof Enemy;
    }

    /** Nearest enemy within r, judged by horizontal distance (the one we'd come down on). */
    private LivingEntity target(double r) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!isEnemy(e) || mc.player.distanceTo(e) > r) continue;
            double d = Math.hypot(e.getX() - mc.player.getX(), e.getZ() - mc.player.getZ());
            if (d < bestD) { bestD = d; best = (LivingEntity) e; }
        }
        return best;
    }

    @Override
    public void onTick() {
        var p = mc.player;
        ticks++;
        if (cooldown > 0) cooldown--;
        // fall tracking: the arc's top since we last moved up
        if (p.onGround() || p.getDeltaMovement().y > 0 || p.isFallFlying()) apexY = p.getY();
        if (rocketRestore != -1) { InvUtil.restore(rocketRestore); rocketRestore = -1; }
        pickTarget();
        if (spam.get()) { exitTicks = 0; spamTick(); return; }
        if (state != State.IDLE) { exitTicks = 40; state = State.IDLE; }
        if (exitTicks > 0) { exitTicks--; safeExit(); }

        if (airTicks > 0 && (--airTicks == 0 || p.onGround() && airTicks < 35)) { airTicks = 0; launchTarget = null; }
        LivingEntity t = target(Math.max(range.get(), 6));
        if (windJump.get()) windJump(t);
        if (autoHit.get() && t != null && !p.onGround() && !p.isFallFlying() && p.getDeltaMovement().y < 0.1) smash(t, minFall.get());
    }

    /**
     * Spam: hold the best non-mace weapon while falling. The smash is judged with ITS charge and base damage (the mace
     * only comes out for the hit), and a sword is charged 12.5 ticks after a rocket where a mace needs 33.
     */
    private void holdWeapon() {
        int slot = WeaponSwap.best(s -> CombatUtil.isWeapon(s) && !s.is(Items.MACE), s -> (int) (CombatUtil.attackDamage(s) * 10) + 1);
        int sel = mc.player.getInventory().getSelectedSlot();
        if (slot == -1 || slot == sel || !Prism.guard().canSwitchSlot()) return;
        InvUtil.swap(slot, false);
    }

    /** Ticks the held item needs for a full-strength (crit) hit after a reset, plus a margin. */
    private int chargeTicks() {
        ItemStack stack = mc.player.getMainHandItem();
        double speed = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
            .compute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
                mc.player.getAttributeBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED), EquipmentSlot.MAINHAND);
        return (int) Math.ceil(20.0 / Math.max(0.1, speed)) + 3;
    }

    /**
     * Smash at the last tick it can land: when the move after this tick's lands us (hitting on the very last tick
     * measured as no crit: the server already had us on the ground) or this tick's move carries us out of reach. Until
     * then: face the target for the hit and stay unsprinted (a sprinting hit is never a crit).
     */
    private boolean smash(LivingEntity t, double minimum) {
        var p = mc.player;
        if (p.isFallFlying() || maceSlot() == -1) return false;
        AABB box = HitboxUtil.attackBox(t);
        Vec3 eye = p.getEyePosition(), move = p.getDeltaMovement();
        double reach = Prism.anticheat().range(6.0);
        // face it for the whole fall: the reach window is a tick or two, and an eased turn started late arrives
        // after it (measured: hit refused, the sent look's ray met the box at 3.7 blocks)
        if (RotationUtil.distanceToBox(eye, box) > reach + 40) return false;
        if (p.isSprinting()) p.setSprinting(false);
        // aim from where this tick's move puts our eye (that's where the server stands us for the next hit)
        // ...at where the target walks to: the look we send now is the one the next tick's hit is judged with
        // (measured: aiming at a strafer's current spot missed it)
        Vec3 nextEye = eye.add(move), tStep = t.position().subtract(t.xo, t.yo, t.zo);
        AABB nextBox = box.move(tStep);
        Prism.rotations().requestFast(RotationUtil.closestPoint(nextBox.deflate(0.1), nextEye).lerp(nextBox.getCenter(), 0.6), 92);
        double dist = RotationUtil.distanceToBox(eye, box);
        debugSmash = String.format(java.util.Locale.ROOT, "dist %.2f ray %.2f pitch %.0f", dist, Prism.rotations().rayDistance(box, reach), Prism.rotations().getServerPitch());
        if (fall() < minimum || dist > reach) return false;
        Vec3 tMove = tStep;
        double next = (move.y - 0.08) * 0.98; // the move after this one
        boolean lands = !mc.level.noCollision(p, p.getBoundingBox().move(move.x * 1.91, Math.min(move.y + next, -0.16), move.z * 1.91));
        boolean leaves = RotationUtil.distanceToBox(nextEye, box.move(tMove)) > reach;
        if (!lands && !leaves && fall() < 8) return false; // past 8 blocks: +1 a block, not worth risking the crit
        boolean hit = CombatUtil.attack(t, false); // onAttack makes sure the mace is the weapon
        if (!hit) debugSmash += " ATTACK REFUSED";
        return hit;
    }

    private void windJump(LivingEntity t) {
        var p = mc.player;
        int charge = InvUtil.findHotbar(Items.WIND_CHARGE);
        if (jumpStage == 0) {
            if (t == null || cooldown > 0 || charge == -1 || !p.onGround() || p.distanceTo(t) > range.get() || maceSlot() == -1) return;
            jumpStage = 1;
        }
        // look straight down first; once the server has that look, throw at our feet and jump in the same tick:
        // the blast goes off right under us while the jump is starting (the classic wind charge launch)
        Prism.rotations().request(p.getYRot(), 90f, 90);
        if (++jumpStage > 8 || charge == -1 || !p.onGround()) { jumpStage = 0; cooldown = 20; return; }
        if (Prism.rotations().getServerPitch() < 85f) return;
        int sel = p.getInventory().getSelectedSlot();
        if (charge != sel && !Prism.guard().canSwitchSlot()) return;
        if (heldBefore == -1) heldBefore = sel;
        InvUtil.swap(charge, false);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        MovementHooks.requestJump();
        // back to the sword next tick: it charges fast, and the hit swaps to the mace for that one hit
        Prism.guard().deferSlot(heldBefore);
        heldBefore = -1;
        launchTarget = t;
        airTicks = 40;
        jumpStage = 0;
        cooldown = 20;
    }

    /** While airborne after a launch (or a Spam drop): where to steer (the target's feet), else null. Read by the move hooks. */
    public Vec3 airChasePoint() {
        LivingEntity t = spam.get() ? (state == State.DROP ? spamTarget : null) : (airTicks == 0 ? null : launchTarget);
        if (t == null || !t.isAlive() || mc.player.onGround() || mc.player.isFallFlying()) return null;
        return Math.hypot(t.getX() - mc.player.getX(), t.getZ() - mc.player.getZ()) < 0.4 ? null : t.position();
    }

    @Override
    public void onAttack(Entity target) {
        if (fall() <= 1.5) return;
        int slot = maceSlot();
        ItemStack held = mc.player.getMainHandItem();
        if (slot != -1 && !held.is(Items.MACE)) WeaponSwap.forThisHit(slot);
    }

    // ==== target picking: right click a player ==================================================================

    /** Pick or drop the Spam target. Also used by tests. */
    public void select(LivingEntity e) {
        picked = e == picked ? null : e;
        if (picked == null) ChatUtil.info("AutoMace: target cleared");
        else ChatUtil.good("AutoMace: target " + picked.getName().getString());
    }

    private void pickTarget() {
        boolean down = mc.gui.screen() == null && KeyUtil.isMouseDown(KeyUtil.MOUSE_RIGHT);
        if (down && !rightWasDown && spam.get()) {
            LivingEntity hit = lookedAt(128);
            if (hit != null) {
                select(hit);
                // swallow the click so it doesn't also use the held item (a rocket would fire)
                while (mc.options.keyUse.consumeClick()) { }
                mc.options.keyUse.setDown(false);
            }
        }
        rightWasDown = down;
        if (picked != null && (picked.isRemoved() || !picked.isAlive())) picked = null;
    }

    /** The enemy under the crosshair up to {@code dist} blocks (hitboxes grown half a block: far players are tiny). */
    public LivingEntity lookedAt(double dist) {
        Vec3 eye = mc.player.getEyePosition(), end = eye.add(mc.player.getViewVector(1f).scale(dist));
        LivingEntity best = null;
        double bestD = dist * dist;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Player || mobs.get() && e instanceof Enemy) || !isEnemy(e)) continue;
            var hit = e.getBoundingBox().inflate(0.5).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.distanceToSqr(hit.get());
            if (d < bestD) { bestD = d; best = (LivingEntity) e; }
        }
        return best;
    }

    // ==== Spam: the elytra loop ====================================================================================

    /** Blocks above the ground under which a rescue glide no longer catches a fast fall. */
    private static final double BAIL_FLOOR = 12;

    /**
     * How high to drop from: Height, but at most 14 over a target that's walking (a 24-block fall takes ~28 ticks, a
     * strafing player is long gone by then; measured: the dummy walked 3 blocks off during the drop).
     */
    private double dropHeight(LivingEntity t) {
        double speed = Math.hypot(t.getX() - t.xo, t.getZ() - t.zo);
        if (speed > 0.06) movingTicks = 40;
        return movingTicks > 0 ? Math.min(height.get(), 14) : height.get();
    }

    private void go(State s) { state = s; stateTicks = 0; chestSwapped = false; }

    private LivingEntity findSpamTarget() {
        if (picked != null && picked.isAlive() && !picked.isRemoved() && mc.player.distanceTo(picked) < 160) return picked;
        return autoTarget.get() ? target(spamRange.get()) : null;
    }

    private void spamTick() {
        var p = mc.player;
        spamTarget = findSpamTarget();
        stateTicks++;
        if (movingTicks > 0) movingTicks--;
        if (state == State.IDLE) {
            if (spamTarget == null) return;
            String why = maceSlot() == -1 ? "a mace in the hotbar" : !hasGlider() ? "an elytra" : !hasChestplate() ? "a chestplate"
                : rockets() == 0 ? "firework rockets" : null;
            if (why != null) {
                if (!why.equals(missing)) ChatUtil.error("AutoMace Spam needs " + why);
                missing = why;
                return;
            }
            missing = null;
            go(State.TAKEOFF);
        }
        LivingEntity t = spamTarget;
        if (t == null) {
            // target gone mid-loop: keep flying safely (elytra on) and wait
            if (!p.onGround() && !p.isFallFlying() && state != State.RECOVER) go(State.RECOVER);
            if (p.onGround() || p.isFallFlying()) { go(State.IDLE); return; }
        }
        switch (state) {
            case TAKEOFF -> takeoff();
            case CLIMB -> climb(t);
            case POSITION -> position(t);
            case DROP -> drop(t);
            case RECOVER -> recover();
            default -> { }
        }
    }

    private void takeoff() {
        var p = mc.player;
        if (!wearingGlider()) { equip(true); return; }
        if (p.isFallFlying()) { go(State.CLIMB); return; }
        if (p.onGround()) { MovementHooks.requestJump(); return; }
        // in the air: open the elytra once falling (jump release -> press, Grim ElytraB-safe)
        if (p.getDeltaMovement().y < 0 && !MovementHooks.glidePending()) MovementHooks.requestGlide();
    }

    /** Rocket straight up until we're Height above the target. */
    private void climb(LivingEntity t) {
        var p = mc.player;
        if (!p.isFallFlying()) { go(p.onGround() ? State.TAKEOFF : State.RECOVER); return; }
        holdWeapon();
        aim(yawTo(t.position()), -88f);
        if (p.getY() - t.getY() >= dropHeight(t)) { go(State.POSITION); return; }
        // the rocket follows the look the server has: only fire once that's steeply up
        boolean urgent = p.getDeltaMovement().y < -0.5; // caught from a fall: any upward-ish rocket helps
        if (Prism.rotations().getServerPitch() < (urgent ? -30f : -60f) || !silent()) rocket(urgent || p.getDeltaMovement().y < 1.0);
        if (stateTicks > 200) go(State.POSITION);
    }

    /**
     * Glide to where letting go lands us on the target: after the swap we keep our horizontal speed, which the air
     * slows by 0.91 a tick, so the drop point leads the target by that drift (and by where the target walks meanwhile).
     */
    private void position(LivingEntity t) {
        var p = mc.player;
        if (!p.isFallFlying()) { go(State.RECOVER); return; }
        holdWeapon();
        double above = p.getY() - t.getY(), want = dropHeight(t);
        if (above < want * 0.6) { go(State.CLIMB); return; }
        int fallTicks = fallTicks(above - 2);
        Vec3 v = p.getDeltaMovement();
        double k = 0.91 * (1 - Math.pow(0.91, fallTicks)) / (1 - 0.91);
        Vec3 tVel = t.position().subtract(t.xo, t.yo, t.zo);
        Vec3 aimAt = t.position().add(tVel.scale(fallTicks));
        double landX = p.getX() + v.x * k, landZ = p.getZ() + v.z * k;
        double off = Math.hypot(aimAt.x - landX, aimAt.z - landZ);
        // every rocket resets the attack charge (26.3: a swinging item use does), and a smash is only a crit (x1.5)
        // when fully charged: let go only if the held weapon has recharged by the time we come down
        boolean charged = ticks - lastRocket + fallTicks >= chargeTicks();
        boolean tooHigh = above > want + 8; // longer falls are faster than the one-tick reach window, and easier to dodge
        if (off < 1.5 && charged && !tooHigh) { go(State.DROP); return; }
        // head for the point we'd have to let go at; glide level (slightly down = faster), climb back if too low
        double dist = Math.hypot(aimAt.x - p.getX(), aimAt.z - p.getZ());
        float pitch = above < want - 4 ? -25f : tooHigh ? 35f : dist > 12 ? 8f : -4f;
        aim(yawTo(new Vec3(aimAt.x - v.x * k + (p.getX() - landX) * 0, 0, aimAt.z - v.z * k)), pitch);
        if (above < want - 4 && p.getDeltaMovement().length() < 0.8) rocket(true);
        if (stateTicks > 160) go(State.CLIMB);
    }

    /** Elytra off (gliding blocks smashes), mace out, fall onto the target and smash at the last tick. */
    private void drop(LivingEntity t) {
        var p = mc.player;
        if (!chestSwapped) {
            if (!equip(false)) return; // wait until a click is allowed
            chestSwapped = true;
            p.stopFallFlying();        // the server stops our glide as soon as it sees the chestplate: match it
            holdWeapon();
            if (p.isSprinting()) p.setSprinting(false);
            return;
        }
        if (p.onGround()) { go(State.TAKEOFF); return; }
        if (p.isFallFlying()) p.stopFallFlying();
        holdWeapon();
        int before = CombatUtil.attacksSent;
        if (smash(t, 1.6) || CombatUtil.attacksSent > before) {
            smashes++;
            go(State.RECOVER);
            return;
        }
        // going to miss? Our horizontal speed decays by 0.91 a tick (air steering adds a little), the target keeps
        // walking. Then open the elytra while there's still height to catch the fall (it takes ~5 ticks).
        double ground = groundBelow(40);
        Vec3 v = p.getDeltaMovement();
        int left = fallTicks(ground, -v.y);
        double k = 0.91 * (1 - Math.pow(0.91, left)) / (1 - 0.91);
        double steer = 0.22 * Math.max(0, left - 11 * (1 - Math.pow(0.91, left)));
        // a strafing target turns around: only trust its walk a few ticks ahead
        Vec3 tVel = t.position().subtract(t.xo, t.yo, t.zo).scale(Math.min(left, 6));
        double miss = Math.hypot(t.getX() + tVel.x - (p.getX() + v.x * k), t.getZ() + tVel.z - (p.getZ() + v.z * k));
        double reach = Prism.anticheat().range(6.0);
        boolean willMiss = miss > reach - 0.2 + steer * 0.5; // air steering barely closes distance in practice
        // last resort: it isn't below us at all (straight above it we're "out of reach" until the last few blocks)
        double horiz = Math.hypot(t.getX() - p.getX(), t.getZ() - p.getZ());
        boolean outOfReach = horiz > reach + 0.5;
        debugDrop = String.format(java.util.Locale.ROOT, "miss %.2f steer %.2f ground %.1f ticks %d hdist %.2f vh %.2f", miss, steer, ground, left,
            horiz, v.horizontalDistance()) + " | " + debugSmash;
        // a rescue takes ~6 ticks (chestplate off, elytra open, turn up, rocket): below BAIL_FLOOR it can't catch the
        // fall any more, so from there on we commit to the hit
        boolean canBail = ground > BAIL_FLOOR + -v.y * 2;
        // the last moment a rescue still works: only commit if it's nearly straight below (a strafer covers ~1.5
        // blocks in the ticks left; measured: committing on predictions alone crash-landed 39 HP over a run)
        boolean lastChance = ground < BAIL_FLOOR + 6 + -v.y * 2;
        if (canBail && (willMiss && ground < BAIL_FLOOR + 14 || outOfReach || lastChance && horiz > 0.8)) go(State.RECOVER);
    }

    /** Elytra back on and gliding (the smash leaves us hanging at 0.01 up, so it opens right away), then climb again. */
    private void recover() {
        var p = mc.player;
        aim(spamTarget != null ? yawTo(spamTarget.position()) : p.getYRot(), -88f);
        if (!wearingGlider()) { equip(true); return; }
        if (p.isFallFlying()) { go(State.CLIMB); return; }
        if (p.onGround()) { go(State.TAKEOFF); return; }
        if (p.getDeltaMovement().y < 0.02 && !MovementHooks.glidePending()) MovementHooks.requestGlide();
        if (stateTicks > 60) go(State.TAKEOFF);
    }

    // ---- Spam helpers

    /** Turn for elytra flight: silently when the move fix makes our physics follow it, else the real view. */
    private void aim(float yaw, float pitch) {
        if (silent()) { Prism.rotations().request(yaw, pitch, 95); return; }
        var p = mc.player;
        float dYaw = Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -30, 30), dPitch = Mth.clamp(pitch - p.getXRot(), -30, 30);
        p.setYRot(p.getYRot() + dYaw);
        p.setXRot(Mth.clamp(p.getXRot() + dPitch, -90, 90));
    }

    private static boolean silent() { return !Prism.anticheat().moveFix.is("Off"); }

    private float yawTo(Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - mc.player.getX()), to.z - mc.player.getZ()));
    }

    /** Ticks a free fall of {@code h} blocks takes (gravity 0.08, drag 0.98 a tick). */
    private int fallTicks(double h) { return fallTicks(h, 0); }

    /** Ticks to fall {@code h} blocks already falling at {@code speed} b/t. */
    private int fallTicks(double h, double speed) {
        double vy = Math.max(0, speed), fallen = 0;
        int n = 0;
        while (fallen < h && n < 200) { vy = (vy + 0.08) * 0.98; fallen += vy; n++; }
        return n;
    }

    private double groundBelow(double max) {
        AABB box = mc.player.getBoundingBox();
        for (double d = 0.5; d <= max; d += 0.5) if (!mc.level.noCollision(mc.player, box.move(0, -d, 0))) return d;
        return max + 1;
    }

    private void rocket(boolean want) {
        var p = mc.player;
        if (!want || ticks - lastRocket < (p.getDeltaMovement().y < -0.5 ? 4 : 12) || !p.isFallFlying()) return;
        if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
        } else {
            int slot = InvUtil.findHotbar(Items.FIREWORK_ROCKET);
            if (slot == -1) return;
            int prev = p.getInventory().getSelectedSlot();
            if (slot != prev) {
                if (!Prism.guard().canSwitchSlot()) return;
                InvUtil.swap(slot, false);
                rocketRestore = prev;
            }
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
        lastRocket = ticks;
    }

    private boolean rocketsInOffhand() { return mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET); }

    private int rockets() {
        int n = rocketsInOffhand() ? mc.player.getOffhandItem().getCount() : 0;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) n += mc.player.getInventory().getItem(i).getCount();
        return n;
    }

    private static boolean isGlider(ItemStack s) { return LivingEntity.canGlideUsing(s, EquipmentSlot.CHEST); }

    private static boolean isChestplate(ItemStack s) {
        Equippable eq = s.get(DataComponents.EQUIPPABLE);
        return eq != null && eq.slot() == EquipmentSlot.CHEST && !isGlider(s);
    }

    private boolean wearingGlider() { return isGlider(mc.player.getItemBySlot(EquipmentSlot.CHEST)); }

    private boolean hasGlider() { return wearingGlider() || InvUtil.findInventory(AutoMace::isGlider) != -1; }

    private boolean hasChestplate() { return isChestplate(mc.player.getItemBySlot(EquipmentSlot.CHEST)) || InvUtil.findInventory(AutoMace::isChestplate) != -1; }

    /**
     * Put the elytra ({@code glider}) or the chestplate on, swapping it with whatever's worn. False if we have to wait
     * a tick first (Grim MultiActionsC: no clicks while the server thinks we sprint or hold movement keys).
     */
    private boolean equip(boolean glider) {
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (glider ? isGlider(worn) : isChestplate(worn)) return true;
        int slot = InvUtil.findInventory(glider ? AutoMace::isGlider : AutoMace::isChestplate);
        if (slot == -1 || !InvUtil.canClick()) return false;
        int sync = mc.player.inventoryMenu.containerId;
        if (slot < 9) {
            mc.gameMode.handleContainerInput(sync, 6, slot, ContainerInput.SWAP, mc.player);
        } else {
            int screen = InvUtil.toScreenSlot(slot);
            mc.gameMode.handleContainerInput(sync, screen, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(sync, 6, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(sync, screen, 0, ContainerInput.PICKUP, mc.player);
        }
        return true;
    }

    @Override
    public String getInfo() {
        if (spam.get()) {
            LivingEntity t = spamTarget != null ? spamTarget : picked;
            return state.name() + (t != null ? " " + t.getName().getString() : "");
        }
        return fall() > 1.5 ? String.format(java.util.Locale.ROOT, "%.1f", fall()) : null;
    }
}
