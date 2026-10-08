package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ColorSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.InvUtil;
import dev.prismglass.util.RotationUtil;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Shows where every thrown ender pearl will land and when, yours and other players'. Follows vanilla's throwable tick
 * exactly (gravity 0.03, then drag 0.99 in air / 0.8 in water, then move) and marks the spot the owner actually
 * teleports to: vanilla uses the pearl's position from the start of the impact tick, not the impact point.
 */
public class PearlPredict extends Module {
    public final ColorSetting own = color("OwnColor", 0xFF8AB4FF, "Your pearls.");
    public final ColorSetting others = color("OthersColor", 0xFFFF7A5C, "Other players' pearls.");
    public final BoolSetting labels = bool("Labels", true, "Owner and seconds until it lands.");
    public final BoolSetting path = bool("Path", true, "Draw the flight path.");
    public final NumberSetting maxTicks = num("MaxTicks", 300, 50, 1200, 10, "How far ahead to simulate.");
    public final BoolSetting alerts = bool("Alerts", true, "Toast + sound when an enemy pearl is about to land near you.");
    public final NumberSetting alertRange = num("AlertRange", 12, 3, 40, 1, "Alerts: how close to you the pearl has to land.").visibleWhen(alerts::get);
    public final BoolSetting autoPearl = bool("AutoPearl", false, "When an enemy pearls away, throw yours at the angle that lands closest to where theirs lands.");
    public final NumberSetting followRange = num("FollowRange", 8, 2, 24, 0.5, "AutoPearl: only follow enemies this close when they throw.").visibleWhen(autoPearl::get);
    public final NumberSetting minDistance = num("MinDistance", 6, 2, 40, 1, "AutoPearl: ignore pearls landing closer than this to you.").visibleWhen(autoPearl::get);
    public final NumberSetting maxError = num("MaxError", 3, 0.5, 10, 0.5, "AutoPearl: only throw if you'd land within this many blocks of them.").visibleWhen(autoPearl::get);
    public final ModeSetting prefer = mode("Prefer", "Balanced", "AutoPearl: Balanced = accurate but not slow. Fastest = quickest arc within MaxError. Closest = most accurate arc (often a slow lob).", "Balanced", "Fastest", "Closest").visibleWhen(autoPearl::get);

    private record Landing(Vec3 spot, String label, int color, double sx, double sy) {}

    private final List<Landing> landings = new ArrayList<>();

    public PearlPredict() { super("PearlPredict", "Where (and when) thrown ender pearls land. AutoPearl follows enemies who pearl away.", Category.RENDER); }

    /** Result of a simulated flight: path points, where the owner ends up, ticks until impact. */
    public record Flight(List<Vec3> points, Vec3 teleport, int ticks, Entity hitEntity) {}

    /** Vanilla ThrowableProjectile#tick for a pearl, from its current position and velocity. */
    public static Flight simulate(ThrownEnderpearl pearl, int maxTicks) {
        return simulate(pearl.level(), pearl.position(), pearl.getDeltaMovement(), maxTicks, pearl, pearl.getOwner());
    }

    /**
     * The same flight for any start/velocity (also a pearl that hasn't been thrown yet): gravity 0.03, then drag
     * (0.99 air, 0.8 water), then move; the first block or entity in the way ends it. {@code self} is only the
     * collision context, {@code owner} can't be hit.
     */
    public static Flight simulate(Level level, Vec3 pos, Vec3 vel, int maxTicks, Entity self, Entity owner) {
        List<Vec3> points = new ArrayList<>();
        points.add(pos);
        for (int t = 1; t <= maxTicks; t++) {
            vel = vel.add(0, -0.03, 0);
            boolean water = level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER);
            vel = vel.scale(water ? 0.8 : 0.99);
            Vec3 next = pos.add(vel);
            BlockHitResult block = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            AABB box = new AABB(pos.x - 0.125, pos.y, pos.z - 0.125, pos.x + 0.125, pos.y + 0.25, pos.z + 0.125);
            EntityHitResult entity = ProjectileUtil.getEntityHitResult(level, self, pos, end, box.expandTowards(vel).inflate(1.0),
                e -> e != self && e != owner && e.isPickable() && !e.isSpectator(), 0.3f);
            if (entity != null || block.getType() != HitResult.Type.MISS) {
                points.add(entity != null ? entity.getLocation() : end);
                return new Flight(points, pos, t, entity != null ? entity.getEntity() : null);
            }
            pos = next;
            points.add(pos);
            if (pos.y < level.getMinY() - 64) return new Flight(points, null, t, null); // into the void
        }
        return new Flight(points, null, maxTicks, null);
    }

    // ---- AutoPearl ----------------------------------------------------------------------------------

    /** A throw: rotation, where it would put us, how far that is from the goal, ticks in the air. */
    public record Throw(float yaw, float pitch, Vec3 landing, double error, int ticks) {}

    private final Set<Integer> seen = new HashSet<>();
    private Vec3 followTarget;
    private String followName;
    private int followTicks, restoreSlot = -1;

    /** Our pearl's flight for this rotation: vanilla spawns it at eye - 0.1 and adds our own movement. */
    public Flight simulateThrow(float yaw, float pitch) {
        var p = mc.player;
        Vec3 start = new Vec3(p.getX(), p.getEyeY() - 0.1, p.getZ());
        float rad = (float) (Math.PI / 180.0);
        Vec3 dir = new Vec3(-Mth.sin(yaw * rad) * Mth.cos(pitch * rad), -Mth.sin(pitch * rad), Mth.cos(yaw * rad) * Mth.cos(pitch * rad)).normalize();
        Vec3 move = p.getDeltaMovement();
        Vec3 vel = dir.scale(1.5).add(move.x, p.onGround() ? 0 : move.y, move.z);
        return simulate(mc.level, start, vel, maxTicks.getInt(), p, p);
    }

    /** How far from them we end up: you fall straight down from the teleport spot, so height counts half. */
    private static double landError(Vec3 landing, Vec3 goal) {
        return Math.hypot(landing.x - goal.x, landing.z - goal.z) + 0.5 * Math.abs(landing.y - goal.y);
    }

    private Throw evaluate(float yaw, float pitch, Vec3 goal) {
        Flight f = simulateThrow(yaw, pitch);
        if (f.teleport() == null) return new Throw(yaw, pitch, null, Double.MAX_VALUE, f.ticks());
        return new Throw(yaw, pitch, f.teleport(), landError(f.teleport(), goal), f.ticks());
    }

    /**
     * The best rotation to land next to {@code goal}: sweep the pitch along the direct yaw, take every local
     * minimum (the low and the high arc), polish each by alternating yaw/pitch steps, then pick by Prefer.
     */
    public Throw solve(Vec3 goal) {
        var p = mc.player;
        float baseYaw = (float) Math.toDegrees(Math.atan2(-(goal.x - p.getX()), goal.z - p.getZ()));
        Throw[] sweep = new Throw[181];
        for (int i = 0; i <= 180; i++) sweep[i] = evaluate(baseYaw, i - 90f, goal);
        List<Throw> candidates = new ArrayList<>();
        for (int i = 0; i <= 180; i++) {
            double e = sweep[i].error();
            if (e == Double.MAX_VALUE) continue;
            boolean min = (i == 0 || e <= sweep[i - 1].error()) && (i == 180 || e <= sweep[i + 1].error());
            if (min) candidates.add(refine(sweep[i], goal));
        }
        Throw best = null;
        for (Throw t : candidates) {
            if (best == null) { best = t; continue; }
            boolean tOk = t.error() <= maxError.get(), bestOk = best.error() <= maxError.get();
            if (prefer.is("Closest")) { if (t.error() < best.error()) best = t; }
            else if (prefer.is("Balanced")) { if (t.error() + 0.04 * t.ticks() < best.error() + 0.04 * best.ticks()) best = t; } // 1 s of flight ~ 0.8 blocks
            else if (tOk && (!bestOk || t.ticks() < best.ticks())) best = t;    // Fastest: quickest arc that's close enough
            else if (!tOk && !bestOk && t.error() < best.error()) best = t;
        }
        return best;
    }

    private Throw refine(Throw t, Vec3 goal) {
        float step = 0.5f;
        for (int round = 0; round < 14 && step > 0.02f; round++) {
            boolean improved = false;
            for (float[] d : new float[][]{{step, 0}, {-step, 0}, {0, step}, {0, -step}}) {
                float pitch = Math.max(-90f, Math.min(90f, t.pitch() + d[1]));
                Throw n = evaluate(t.yaw() + d[0], pitch, goal);
                if (n.error() < t.error()) { t = n; improved = true; }
            }
            if (!improved) step *= 0.5f;
        }
        return t;
    }

    @Override
    public void onTick() {
        if (restoreSlot != -1) { InvUtil.restore(restoreSlot); restoreSlot = -1; }
        if (alerts.get()) alertPearls();
        if (!autoPearl.get()) { followTarget = null; return; }
        // an enemy just threw a pearl near us: follow where it lands
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ThrownEnderpearl pearl) || !pearl.isAlive() || !seen.add(pearl.getId())) continue;
            boolean test = testPearls.remove(pearl.getId());
            if (!test && (!(pearl.getOwner() instanceof Player owner) || owner == mc.player
                || Prism.friends().isFriend(owner) || owner.distanceTo(mc.player) > followRange.get())) continue;
            Flight f = simulate(pearl, maxTicks.getInt());
            if (f.teleport() == null || f.teleport().distanceTo(mc.player.position()) < minDistance.get()) continue;
            followTarget = f.teleport();
            followName = test ? "test enemy" : pearl.getOwner().getName().getString();
            followTicks = 0;
        }
        seen.removeIf(id -> mc.level.getEntity(id) == null);
        if (followTarget != null) follow();
    }

    private final Set<Integer> alerted = new HashSet<>();

    /** Enemy pearl landing near you: one toast + ping per pearl, with who and how long until they're there. */
    private void alertPearls() {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ThrownEnderpearl pearl) || !pearl.isAlive() || alerted.contains(pearl.getId())) continue;
            if (!(pearl.getOwner() instanceof Player owner) || owner == mc.player || Prism.friends().isFriend(owner)) continue;
            Flight f = simulate(pearl, maxTicks.getInt());
            if (f.teleport() == null) continue;
            double d = f.teleport().distanceTo(mc.player.position());
            if (d > alertRange.get()) continue;
            alerted.add(pearl.getId());
            dev.prismglass.module.client.Hud.message(String.format("%s pearls %.0fm from you in %.1fs", owner.getName().getString(), d, f.ticks() / 20.0), 0xFFFF7A5C);
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 1.6f));
        }
        alerted.removeIf(id -> mc.level.getEntity(id) == null);
    }

    private void follow() {
        var p = mc.player;
        if (++followTicks > 20) {
            ChatUtil.info("AutoPearl: no clean throw at " + followName + " (the spot is too small or blocked)");
            followTarget = null;
            return;
        }
        InteractionHand hand;
        int slot = -1;
        if (p.getOffhandItem().is(Items.ENDER_PEARL)) hand = InteractionHand.OFF_HAND;
        else {
            slot = InvUtil.findHotbar(Items.ENDER_PEARL);
            if (slot == -1) { ChatUtil.error("AutoPearl: no pearls in your hotbar"); followTarget = null; return; }
            hand = InteractionHand.MAIN_HAND;
        }
        ItemStack stack = hand == InteractionHand.OFF_HAND ? p.getOffhandItem() : p.getInventory().getItem(slot);
        if (p.getCooldowns().isOnCooldown(stack)) return;
        Throw t = solve(followTarget);
        if (t == null || t.error() > maxError.get()) {
            if (followTicks > 4) { ChatUtil.info("AutoPearl: no throw lands near " + followName + " from here"); followTarget = null; }
            return;
        }
        Prism.rotations().request(t.yaw(), t.pitch(), 95);
        // the throw goes where the server last saw us look: only once that rotation itself lands close enough
        var rot = Prism.rotations();
        if (!rot.isActive() || RotationUtil.angleDiff(rot.getServerYaw(), rot.getServerPitch(), t.yaw(), t.pitch()) > 1.5f) return;
        Flight check = simulateThrow(rot.getServerYaw(), rot.getServerPitch());
        if (check.teleport() == null || landError(check.teleport(), followTarget) > maxError.get()) return;
        if (slot != -1 && slot != p.getInventory().getSelectedSlot()) {
            if (!Prism.guard().canSwitchSlot()) return;
            restoreSlot = p.getInventory().getSelectedSlot();
            InvUtil.swap(slot, false);
        }
        mc.gameMode.useItem(p, hand);
        lastThrow = check.teleport();
        ChatUtil.good(String.format("AutoPearl: following %s (%.1f blocks off, %.1fs)", followName,
            landError(check.teleport(), followTarget), check.ticks() / 20.0));
        followTarget = null;
    }

    /** Dev: where our last AutoPearl throw was predicted to land. */
    public Vec3 lastThrow;

    /** Pearls spawned by {@code .pearltest}: followed as if an enemy threw them. */
    private final Set<Integer> testPearls = new HashSet<>();

    /**
     * Singleplayer test ({@code .pearltest [distance]}): an "enemy" pearl is thrown from right next to you in a
     * random direction, about {@code distance} blocks far. PearlPredict shows it; AutoPearl (if on) follows it.
     */
    public void spawnTestPearl(double distance) {
        var server = mc.getSingleplayerServer();
        if (server == null) { ChatUtil.error("PearlTest only works in singleplayer"); return; }
        var sp = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (sp == null) return;
        double angle = Math.random() * Math.PI * 2;
        // pitch for that distance on flat ground: range of a 1.5 b/t throw is ~ distance at a shallow angle
        float pitch = (float) -Math.max(5, Math.min(45, 4 + distance * 0.55));
        server.execute(() -> {
            var level = sp.level();
            ThrownEnderpearl pearl = new ThrownEnderpearl(net.minecraft.world.entity.EntityType.ENDER_PEARL, level);
            pearl.setPos(sp.getX() + Math.cos(angle + 1.6) * 1.5, sp.getEyeY() - 0.1, sp.getZ() + Math.sin(angle + 1.6) * 1.5);
            float yaw = (float) Math.toDegrees(angle);
            pearl.shoot(-Mth.sin(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD), -Mth.sin(pitch * Mth.DEG_TO_RAD),
                Mth.cos(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD), 1.5f, 1.0f);
            level.addFreshEntity(pearl);
            int id = pearl.getId();
            mc.execute(() -> testPearls.add(id));
        });
        if (!isEnabled()) setEnabled(true);
        ChatUtil.info("PearlTest: enemy pearl thrown" + (autoPearl.get() ? " - AutoPearl will follow" : " (turn on AutoPearl to follow it)"));
    }

    /** Dev: follow a spot as if an enemy pearl were landing there. */
    public void debugFollow(Vec3 target) { followTarget = target; followName = "test"; followTicks = 0; }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        landings.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ThrownEnderpearl pearl) || !pearl.isAlive()) continue;
            Entity owner = pearl.getOwner();
            boolean mine = owner == mc.player;
            int c = mine ? own.color() : others.color();
            Flight f = simulate(pearl, maxTicks.getInt());
            if (path.get()) {
                for (int i = 1; i < f.points().size(); i++) {
                    Render3D.line(f.points().get(i - 1), f.points().get(i), ColorUtil.fade(c, 1f - 0.7f * i / f.points().size()));
                }
            }
            if (f.teleport() == null) continue;
            Vec3 t = f.teleport();
            Render3D.box(new AABB(t.x - 0.3, t.y, t.z - 0.3, t.x + 0.3, t.y + 1.8, t.z + 0.3), ColorUtil.withAlpha(c, 45), c, true);
            if (labels.get()) {
                double[] s = Render3D.project(t.add(0, 2.2, 0));
                if (s != null) {
                    String who = mine ? "You" : owner != null ? owner.getName().getString() : "Pearl";
                    String hit = f.hitEntity() != null ? " §c(hits " + f.hitEntity().getName().getString() + ")" : "";
                    landings.add(new Landing(t, who + " §7" + String.format("%.1fs", f.ticks() / 20.0) + hit, c, s[0], s[1]));
                }
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (landings.isEmpty()) return;
        Glass.Style style = Prism.modules().get(ClickGui.class).panelStyle();
        for (Landing l : landings) {
            float w = mc.font.width(l.label()) + 14;
            float x = (float) l.sx() - w / 2, y = (float) l.sy() - 13;
            Glass.panel(ctx, x, y, w, 13, style);
            ctx.fill((int) x + 4, (int) y + 4, (int) x + 7, (int) y + 9, l.color());
            ctx.text(mc.font, l.label(), (int) x + 10, (int) y + 3, 0xFFFFFFFF, true);
        }
    }

    @Override
    public String getInfo() { return String.valueOf(landings.size()); }
}
