package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.combat.KillAura;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only automated combat test (./gradlew runClient -Pcombattest): creates a flat world, spawns a
 * zombie 9 blocks away, enables KillAura with AutoWalk, logs what happens, prints attack-timing stats and
 * exits. Never active in normal play.
 */
public final class CombatTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, inWorldTicks;
    private static boolean worldRequested, setupDone;
    private static Zombie zombie;
    private static final List<Integer> attackTicks = new ArrayList<>();
    private static final List<Integer> swingTicks = new ArrayList<>();
    private static int crits;
    private static float startDistance = -1;

    private CombatTest() {}

    public static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    /** Called from Prism.onPacketSend in test mode. */
    public static void onSend(Object packet) {
        if (!setupDone) return;
        if (packet instanceof ServerboundAttackPacket) {
            attackTicks.add(inWorldTicks);
            if (!mc.player.onGround() && mc.player.getDeltaMovement().y < 0) crits++;
        }
        if (packet instanceof ServerboundSwingPacket) swingTicks.add(inWorldTicks);
    }

    private static void log(String s) { Prism.LOG.info("[combattest] {}", s); }

    private static void tick() {
        ticks++;
        if (!worldRequested && ticks == 80 && mc.level == null) {
            worldRequested = true;
            String name = "prismtest-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
            log("creating world " + name);
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(),
                WorldPresets::createFlatWorldDimensions, null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        inWorldTicks++;
        MinecraftServer server = mc.getSingleplayerServer();

        if (inWorldTicks == 40) {
            run(server, "gamerule doDaylightCycle false");
            run(server, "gamerule doMobSpawning false");
            run(server, "time set day");
            run(server, "kill @e[type=!player]");
            run(server, "item replace entity @p weapon.mainhand with netherite_sword");
            run(server, "execute at @p run summon zombie ~9 ~ ~ {NoAI:1b,PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:400}],Health:400f}");
        }
        if (inWorldTicks == 60) {
            for (Entity e : mc.level.entitiesForRendering()) if (e instanceof Zombie z) zombie = z;
            if (zombie == null) { log("FAIL: zombie not found"); Runtime.getRuntime().halt(1); }
            Prism.anticheat().preset.parse(System.getProperty("prismglass.preset", "Vulcan"));
            Prism.anticheat().update();
            KillAura ka = Prism.modules().get(KillAura.class);
            ka.hostiles.set(true);
            ka.players.set(false);
            ka.autoWalk.set(true);
            ka.setEnabled(true);
            var crit = Prism.modules().get(dev.prismglass.module.combat.Criticals.class);
            crit.mode.parse("Jump");
            crit.setEnabled(true);
            startDistance = mc.player.distanceTo(zombie);
            setupDone = true;
            log(String.format("setup: distance %.2f, preset %s", startDistance, Prism.anticheat().preset.get()));
        }
        if (setupDone && inWorldTicks % 10 == 0) {
            LivingEntity serverZombie = (LivingEntity) server.overworld().getEntity(zombie.getUUID());
            log(String.format("t=%d dist=%.2f zombieHP=%.1f fwd=%.2f side=%.2f sprint=%s rotActive=%s attacks=%d",
                inWorldTicks, mc.player.distanceTo(zombie), serverZombie == null ? -1f : serverZombie.getHealth(),
                mc.player.input.getMoveVector().y, mc.player.input.getMoveVector().x, mc.player.isSprinting(),
                Prism.rotations().isActive(), attackTicks.size()));
        }
        // layering check: Prism HUD must draw over an open vanilla screen
        if (inWorldTicks == 400) mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
        if (inWorldTicks == 420) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-hudtop.png",
            mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        if (inWorldTicks == 430) {
            // list editor icons need a loaded world on 26.1 (item components), so check them here
            var gui = new dev.prismglass.gui.ClickGuiScreen();
            mc.setScreen(gui);
            var x = Prism.modules().get(dev.prismglass.module.render.Xray.class);
            gui.debugList(x, x.blocks, "gold");
        }
        if (inWorldTicks == 445) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-listicons.png",
            mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        if (inWorldTicks == 455) mc.setScreen(null);
        if (inWorldTicks == 460) {
            summarize();
            Runtime.getRuntime().halt(0);
        }
    }

    private static void summarize() {
        log("attacks=" + attackTicks.size() + " swings=" + swingTicks.size() + " crits=" + crits);
        if (attackTicks.size() > 2) {
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < attackTicks.size(); i++) gaps.add(attackTicks.get(i) - attackTicks.get(i - 1));
            double mean = gaps.stream().mapToInt(Integer::intValue).average().orElse(0);
            double var = gaps.stream().mapToDouble(g -> (g - mean) * (g - mean)).average().orElse(0);
            log(String.format("attack gaps (ticks): %s", gaps));
            log(String.format("gap mean %.2f ticks, stddev %.2f (%.0f%% of mean)", mean, Math.sqrt(var), 100 * Math.sqrt(var) / Math.max(mean, 1e-6)));
        }
        log(String.format("walked from %.2f to %.2f", startDistance, zombie == null ? -1 : mc.player.distanceTo(zombie)));
    }

    private static void run(MinecraftServer server, String cmd) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }
}
