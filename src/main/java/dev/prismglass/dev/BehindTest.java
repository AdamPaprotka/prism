package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.combat.KillAura;
import dev.prismglass.module.movement.AutoWalk;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only test of AutoWalk Behind (./gradlew runClient -Pbehindtest), measured on the integrated server:
 * <ol>
 *   <li>Turner: a NoAI zombie that turns toward you at 3 deg/tick (slower than the ~5 deg/tick orbit), with a lava pit on one
 *       flank. AutoWalk Behind + KillAura. Reports how much of the time you were behind it, distances, hits, and
 *       fails if you ever touch lava or fire.</li>
 *   <li>Statue: the same zombie stops turning; you must settle in the back spot.</li>
 *   <li>Real zombie (AI): hits taken in 200 ticks with KillAura WalkMode Chase, then with WalkMode Behind
 *       (AutoWalk off: KillAura uses AutoWalk's Behind settings on its own).</li>
 * </ol>
 */
public final class BehindTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t;
    private static boolean worldRequested;
    private static UUID zombie;
    private static String phase = "";
    private static int phaseTicks, behind60, behind90, attacks, lavaTicks, fireTicks, hits;
    private static double distSum, distMin = 99, distMax;
    private static float lastHealth = -1;
    private static int failures;

    private BehindTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    public static void onSend(Object packet) { if (packet instanceof ServerboundAttackPacket) attacks++; }

    private static void log(String s) { Prism.LOG.info("[behindtest] {}", s); }

    private static void tick() {
        ticks++;
        if (!worldRequested && ticks == 80 && mc.level == null) {
            worldRequested = true;
            String name = "prismtest-behind-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(), r -> r.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        t++;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.options.pauseOnLostFocus = false;

        if (t == 40) {
            run(server, "gamerule advance_time false");
            run(server, "gamerule spawn_mobs false");
            run(server, "time set day");
            run(server, "kill @e[type=!player]");
            run(server, "item replace entity @p weapon.mainhand with netherite_sword");
            run(server, "effect give @p resistance 1000 3 true");
            int px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
            // zombie 6 blocks east
            run(server, String.format(Locale.ROOT, "summon zombie %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:400}],Health:400f}", px + 6, py, pz));
        }
        if (t == 60) {
            for (Entity e : mc.level.entitiesForRendering()) if (e instanceof Zombie z) zombie = z.getUUID();
            if (zombie == null) { log("FAIL: zombie not found"); Runtime.getRuntime().halt(1); }
            Prism.anticheat().preset.parse("Grim");
            Prism.anticheat().update();
            KillAura ka = Prism.modules().get(KillAura.class);
            ka.hostiles.set(true);
            ka.players.set(false);
            ka.autoWalk.set(false);
            ka.setEnabled(true);
            AutoWalk aw = Prism.modules().get(AutoWalk.class);
            aw.mode.parse("Behind");
            aw.setEnabled(true);
            start("turner");
        }
        if (t == 360) { report(); start("statue"); }
        if (t == 460) {
            report();
            AutoWalk aw = Prism.modules().get(AutoWalk.class);
            double behindAngle = lastDelta, d = lastDist;
            check("statue settles behind (|delta| <= cone)", Math.abs(behindAngle) <= aw.cone.get() + 1);
            check("statue distance about Distance", Math.abs(d - aw.distance.get()) < 0.5);
            // hazard: turn the statue round so its back spot is where we'd have to cross, put lava on that spot
            // and a pit down to the void (the superflat floor is 4 deep) on one flank
            server.execute(() -> {
                Entity e = server.overworld().getEntity(zombie);
                if (e instanceof LivingEntity z) {
                    float yaw = z.getYHeadRot() + 180;
                    z.setYRot(yaw); z.setYHeadRot(yaw); z.setYBodyRot(yaw);
                    double back = Math.toRadians(yaw - 90);
                    int bx = Mth.floor(z.getX() + Math.cos(back) * 2.4), bz = Mth.floor(z.getZ() + Math.sin(back) * 2.4), y = Mth.floor(z.getY()) - 1;
                    run(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d lava", bx - 1, y, bz - 1, bx + 1, y, bz + 1));
                    double flank = Math.toRadians(yaw);
                    int fx = Mth.floor(z.getX() + Math.cos(flank) * 2.4), fz = Mth.floor(z.getZ() + Math.sin(flank) * 2.4);
                    run(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", fx - 1, y - 3, fz - 1, fx + 1, y, fz + 1));
                }
            });
            startY = mc.player.getY();
            start("hazard");
        }
        if (t == 560) {
            report();
            check("hazard: never fell into the pit", minY > startY - 0.5);
            Prism.modules().get(AutoWalk.class).setEnabled(false);
            run(server, "kill @e[type=zombie]");
            int px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
            run(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d grass_block", px - 12, py - 1, pz - 12, px + 12, py - 1, pz + 12));
            run(server, String.format(Locale.ROOT, "summon zombie %d.5 %d %d.5 {PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:400}],Health:400f}", px + 6, py, pz));
        }
        if (t == 580) {
            zombie = null;
            for (Entity e : mc.level.entitiesForRendering()) if (e instanceof Zombie z) zombie = z.getUUID();
            KillAura ka = Prism.modules().get(KillAura.class);
            ka.autoWalk.set(true);
            ka.walkMode.parse("Chase");
            start("chase");
        }
        if (t == 780) {
            report();
            // a fresh zombie 6 blocks away, same as the chase phase got
            run(server, "kill @e[type=zombie]");
            int px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
            run(server, String.format(Locale.ROOT, "summon zombie %d.5 %d %d.5 {PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:400}],Health:400f}", px + 6, py, pz));
            phase = "";
        }
        if (t == 800) {
            zombie = null;
            for (Entity e : mc.level.entitiesForRendering()) if (e instanceof Zombie z) zombie = z.getUUID();
            Prism.modules().get(KillAura.class).walkMode.parse("Behind");
            start("behind");
        }
        if (t == 1000) {
            int chaseHits = lastHits;
            report();
            log(String.format(Locale.ROOT, "real zombie hits taken: chase %d, behind %d", chaseHits, lastHits));
            check("behind takes fewer hits than chase", lastHits < chaseHits || lastHits == 0);
            log(failures == 0 ? "PASS" : "FAILURES " + failures);
            Runtime.getRuntime().halt(failures == 0 ? 0 : 2);
        }

        if (!phase.isEmpty() && zombie != null) server.execute(() -> serverTick(server));
    }

    private static volatile double lastDelta, lastDist;
    private static int lastHits;
    private static double startY, minY = 1e9;

    /** Runs on the server thread: turn the turner, measure where we stand relative to the zombie's back. */
    private static void serverTick(MinecraftServer server) {
        Entity e = server.overworld().getEntity(zombie);
        ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
        if (!(e instanceof LivingEntity z) || p == null) return;
        if (phase.equals("turner")) {
            float want = (float) Math.toDegrees(Math.atan2(p.getZ() - z.getZ(), p.getX() - z.getX())) - 90;
            float yaw = z.getYHeadRot() + Mth.clamp(Mth.wrapDegrees(want - z.getYHeadRot()), -3, 3);
            z.setYRot(yaw);
            z.setYHeadRot(yaw);
            z.setYBodyRot(yaw);
        }
        double back = Mth.wrapDegrees(z.getYHeadRot() - 90);
        double phi = Math.toDegrees(Math.atan2(p.getZ() - z.getZ(), p.getX() - z.getX()));
        double delta = Mth.wrapDegrees(back - phi);
        double dist = Math.hypot(p.getX() - z.getX(), p.getZ() - z.getZ());
        lastDelta = delta;
        lastDist = dist;
        phaseTicks++;
        if (Math.abs(delta) <= 60) behind60++;
        if (Math.abs(delta) <= 90) behind90++;
        distSum += dist;
        distMin = Math.min(distMin, dist);
        distMax = Math.max(distMax, dist);
        minY = Math.min(minY, p.getY());
        if (p.isInLava()) lavaTicks++;
        if (p.isOnFire()) fireTicks++;
        if (lastHealth >= 0 && p.getHealth() < lastHealth - 0.01f) hits++;
        lastHealth = p.getHealth();
        if (phaseTicks % 20 == 0) log(String.format(Locale.ROOT, "%s t=%d delta=%.0f dist=%.2f hp=%.1f", phase, phaseTicks, delta, dist, p.getHealth()));
    }

    private static void start(String name) {
        phase = name;
        phaseTicks = behind60 = behind90 = attacks = lavaTicks = fireTicks = hits = 0;
        distSum = 0; distMin = 99; distMax = 0; lastHealth = -1;
    }

    private static void report() {
        int n = Math.max(1, phaseTicks);
        log(String.format(Locale.ROOT, "[%s] ticks=%d behind60=%.0f%% behind90=%.0f%% dist avg %.2f min %.2f max %.2f attacks=%d hits=%d lava=%d fire=%d",
            phase, phaseTicks, 100.0 * behind60 / n, 100.0 * behind90 / n, distSum / n, distMin, distMax, attacks, hits, lavaTicks, fireTicks));
        lastHits = hits;
        check(phase + ": never in lava or on fire", lavaTicks == 0 && fireTicks == 0);
        if (phase.equals("turner")) {
            check("turner: behind half at least half the time", behind90 > n * 0.5);
            check("turner: attacked", attacks > 3);
        }
    }

    private static void check(String what, boolean ok) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private static void run(MinecraftServer server, String cmd) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }
}
