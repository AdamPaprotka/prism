package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.movement.ElytraBot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Dev only (-Pelytrabottest): real terrain, survival, ElytraBot ~720 blocks from the ground; logs progress. */
public final class ElytraBotTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t;
    private static boolean requested, started;
    private static double tx, tz;
    private static float hp0;

    private ElytraBotTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[ebtest] {}", s); }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismeb-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1234567890123L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (!started && (mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
            || !mc.level.hasChunkAt(mc.player.blockPosition()))) return; // terrain still generating
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        if (t == 30) {
            for (var m : Prism.modules().all()) {
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            }
            run(s, "item replace entity @p armor.chest with elytra");
            run(s, "item replace entity @p hotbar.1 with firework_rocket 64");
            run(s, "time set day");
        }
        if (t == 60) {
            if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
            boolean wall = Boolean.getBoolean("prismglass.ebwall");
            tx = mc.player.getX() + 600;
            tz = wall ? mc.player.getZ() : mc.player.getZ() + 400;
            if (wall) {
                // 61 wide stone wall up to build height 45 blocks ahead: impossible to climb over, so it has to go around
                int x = (int) mc.player.getX() + 45, y = (int) mc.player.getY(), z = (int) mc.player.getZ();
                run(s, String.format("fill %d %d %d %d %d %d stone", x, y - 6, z - 30, x, 319, z + 30));
                log("wall at x=" + x + " z " + (z - 30) + ".." + (z + 30) + " up to y=319");
            }
            hp0 = mc.player.getHealth();
            Prism.modules().get(ElytraBot.class).goTo(tx, tz);
            started = true;
            log(String.format("start at %.0f %.0f %.0f -> %.0f %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ(), tx, tz));
        }
        ElytraBot bot = Prism.modules().get(ElytraBot.class);
        if (started && mc.player.horizontalCollision && mc.player.isFallFlying()) log(String.format("COLLISION at %.0f %.0f %.0f speed %.2f",
            mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getDeltaMovement().length()));
        if (started && t < 300 && t % 20 == 0) {
            var p = mc.player;
            log(String.format("dbg t=%d %s flying=%s ground=%s water=%s vel=%s pitch=%.1f yaw=%.1f rockets=%d sel=%d main=%s",
                t, bot.state(), p.isFallFlying(), p.onGround(), p.isInWater(), p.getDeltaMovement(), p.getXRot(), p.getYRot(),
                p.getInventory().getItem(1).getCount(), p.getInventory().getSelectedSlot(), p.getMainHandItem().getItem()));
        }
        if (started && t % 100 == 0) {
            log(String.format("t=%ds %s pos %.0f %.0f %.0f speed %.2f hp %.1f left %.0fm", t / 20, bot.isEnabled() ? bot.state() : "OFF",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getDeltaMovement().length(), mc.player.getHealth(),
                Math.hypot(tx - mc.player.getX(), tz - mc.player.getZ())));
        }
        if (started && (!bot.isEnabled() && t > 80 || t > 60 + 20 * 120)) {
            log(String.format("END after %ds: %.0fm from target, onGround=%s, hp %.1f -> %.1f", (t - 60) / 20,
                Math.hypot(tx - mc.player.getX(), tz - mc.player.getZ()), mc.player.onGround(), hp0, mc.player.getHealth()));
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }
}
