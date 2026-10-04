package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.combat.CrystalAura;
import dev.prismglass.util.DamageUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * Dev only (-Pcrystaltest): predicted crystal damage vs the server's real health loss (zombie in iron armour + you,
 * Normal difficulty), then CrystalAura v2 live against the zombie.
 */
public final class CrystalTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t;
    private static boolean requested;
    private static Zombie zombie;
    private static float predZombie, predSelf, hpZombie, hpSelf;
    private static final int[][] SPOTS = {{3, 1}, {5, -1}, {2, -1}};
    private static int spot;
    private static int placed;

    private CrystalTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[crystaltest] {}", s); }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static float serverHp(MinecraftServer s, java.util.UUID id) {
        return s.submit(() -> {
            var e = s.overworld().getEntity(id);
            return e instanceof LivingEntity le ? le.getHealth() + le.getAbsorptionAmount() : -1f;
        }).join();
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            mc.options.pauseOnLostFocus = false;
            String name = "prismcrystal-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(), WorldPresets::createFlatWorldDimensions, null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        BlockPos me = mc.player.blockPosition();
        if (t == 30) {
            run(s, "gamerule spawn_mobs false");
            run(s, "kill @e[type=!player]");
            run(s, "fill ~-6 ~-1 ~-6 ~12 ~-1 ~6 obsidian");
            for (String slot : new String[]{"head", "chest", "legs", "feet"}) {
                String item = switch (slot) { case "head" -> "netherite_helmet"; case "chest" -> "netherite_chestplate"; case "legs" -> "netherite_leggings"; default -> "netherite_boots"; };
                run(s, "item replace entity @p armor." + slot + " with " + item + "[enchantments={\"minecraft:blast_protection\":4}]");
            }
            run(s, "item replace entity @p hotbar.0 with end_crystal 64");
            // the zombie stands in a hole (obsidian all round), like a real target, so blasts can't push it away
            for (String off : new String[]{"~3 ~ ~", "~5 ~ ~", "~4 ~ ~1", "~4 ~ ~-1"}) run(s, "setblock " + off + " obsidian");
            run(s, "summon zombie ~4 ~ ~0 {NoAI:1b,PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:500},{id:\"minecraft:explosion_knockback_resistance\",base:1.0},{id:\"minecraft:knockback_resistance\",base:1.0}],Health:500f,"
                + "equipment:{head:{id:\"iron_helmet\"},chest:{id:\"iron_chestplate\"},legs:{id:\"iron_leggings\"},feet:{id:\"iron_boots\"}}}");
        }
        if (t == 50) {
            for (var e : mc.level.entitiesForRendering()) if (e instanceof Zombie z) zombie = z;
            if (zombie == null) { log("FAIL no zombie"); Runtime.getRuntime().halt(1); }
        }
        // three measured explosions: spawn crystal, predict, hit it, compare
        int phase = (t - 60) / 30, sub = (t - 60) % 30;
        if (t >= 60 && phase < SPOTS.length) {
            int[] d = SPOTS[phase];
            BlockPos base = me.offset(d[0], -1, d[1]);
            if (sub == 0) run(s, "summon end_crystal " + (base.getX() + 0.5) + " " + (base.getY() + 1) + " " + (base.getZ() + 0.5) + " {ShowBottom:0b}");
            if (sub == 6) {
                EndCrystal crystal = null;
                for (var e : mc.level.entitiesForRendering()) if (e instanceof EndCrystal c) crystal = c;
                if (crystal == null) { log("no crystal at spot " + phase); return; }
                Vec3 c = crystal.position();
                predZombie = DamageUtil.crystalDamage(zombie, c);
                predSelf = DamageUtil.crystalDamage(mc.player, c);
                hpZombie = serverHp(s, zombie.getUUID());
                hpSelf = serverHp(s, mc.player.getUUID());
                mc.gameMode.attack(mc.player, crystal);
            }
            if (sub == 14) {
                float realZombie = hpZombie - serverHp(s, zombie.getUUID());
                float realSelf = hpSelf - serverHp(s, mc.player.getUUID());
                log(String.format("spot %d: zombie predicted %.2f real %.2f | self predicted %.2f real %.2f", phase, predZombie, realZombie, predSelf, realSelf));
                run(s, "effect give @p instant_health 1 5 true");
            }
        }
        int liveStart = 60 + SPOTS.length * 30 + 20;
        if (t == liveStart) {
            if (mc.screen != null) mc.setScreen(null);
            log(String.format("zombie %.1f blocks away", mc.player.distanceTo(zombie)));
            hpZombie = serverHp(s, zombie.getUUID());
            CrystalAura ca = Prism.modules().get(CrystalAura.class);
            ca.mobs.set(true);
            ca.players.set(false);
            ca.minDamage.set(4.0);
            ca.setEnabled(true);
        }
        if (t == liveStart + 10 || t == liveStart + 40) log("ca: " + Prism.modules().get(CrystalAura.class).debugState() + " paused=" + mc.isPaused() + " screen=" + mc.screen);
        if (t > liveStart && t <= liveStart + 200) {
            for (var e : mc.level.entitiesForRendering()) if (e instanceof EndCrystal c && c.tickCount == 1) placed++;
        }
        if (t == liveStart + 200) {
            Prism.modules().get(CrystalAura.class).setEnabled(false);
            log(String.format("live 10s: %d crystals seen spawning, zombie lost %.1f hp, you have %.1f hp, alive=%s",
                placed, hpZombie - serverHp(s, zombie.getUUID()), serverHp(s, mc.player.getUUID()), mc.player.isAlive()));
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }
}
