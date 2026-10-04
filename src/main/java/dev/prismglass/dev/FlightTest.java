package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.movement.Flight;
import dev.prismglass.module.render.Freecam;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev only (-Pflighttest): Flight (Vanilla) with jump held. With Freecam on the player must not rise (the key steers
 * the camera); with Freecam off it must rise (proves the test presses the key at all).
 */
public final class FlightTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t;
    private static boolean requested;
    private static double y0;
    private static boolean pearlDone, seenServer;
    private static dev.prismglass.module.render.PearlPredict.Flight predicted;
    private static int predictedAtTick, lastServerAge;
    private static net.minecraft.world.phys.Vec3 lastServerPos;

    private static String fmt(net.minecraft.world.phys.Vec3 v) {
        return v == null ? "null" : String.format("(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }

    private FlightTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[flighttest] {}", s); }

    private static boolean rejects(String code) {
        try { Prism.config().importCode(code, "x"); return false; } catch (Exception e) { return true; }
    }

    private static void tick() {
        ticks++;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            mc.options.pauseOnLostFocus = false;
            String name = "prismflight-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(), WorldPresets::createFlatWorldDimensions, null);
            return;
        }
        if (mc.player == null || mc.level == null) return;
        t++;
        Flight flight = Prism.modules().get(Flight.class);
        Freecam freecam = Prism.modules().get(Freecam.class);
        if (t == 40) {
            log("window visible=" + org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_VISIBLE)
                + " focused=" + org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_FOCUSED));
            flight.mode.parse("Vanilla");
            flight.setEnabled(true);
            freecam.setEnabled(true);
        }
        if (t == 50) { y0 = mc.player.getY(); mc.options.keyJump.setDown(true); }
        if (t == 90) {
            log(String.format("freecam ON, jump held 40 ticks: player dy = %.2f (should be ~0)", mc.player.getY() - y0));
            freecam.setEnabled(false);
            y0 = mc.player.getY();
        }
        if (t == 110) {
            log(String.format("freecam OFF, jump held 20 ticks: player dy = %.2f (should be > 0)", mc.player.getY() - y0));
            mc.options.keyJump.setDown(false);
            try {
                String code = Prism.config().exportCode(Prism.config().getActive());
                String name = Prism.config().importCode(code, "roundtrip");
                String back = Prism.config().exportCode(name);
                log("config code " + code.length() + " chars, imported as " + name + ", round trip identical=" + code.equals(back));
                java.nio.file.Files.deleteIfExists(Prism.config().folder().resolve("profiles").resolve(name + ".json"));
                log("bad code rejected: " + rejects("hello"));
            } catch (Exception e) { log("config FAIL " + e); }
            // module code round trip
            try {
                var ka = Prism.modules().get(dev.prismglass.module.combat.KillAura.class);
                var setting = ka.getSettings().stream().filter(st -> st instanceof dev.prismglass.setting.NumberSetting).findFirst().get();
                var num = (dev.prismglass.setting.NumberSetting) setting;
                num.set(num.getMin());
                String code = Prism.config().exportModules(java.util.List.of(ka));
                num.reset();
                var applied = Prism.config().importModules(code, null);
                log("module code " + code.length() + " chars, applied " + applied + ", " + num.getName() + " restored=" + (num.get().doubleValue() == num.getMin()));
                num.reset();
            } catch (Exception e) { log("module code FAIL " + e); }
            // pearl: summon one with a known motion, record the server's real path, compare with the prediction
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "execute at @p run summon ender_pearl ~3 ~25 ~3 {Motion:[0.9d,0.45d,0.55d],Tags:[\"prismtest\"]}"));
        }
        if (t > 110 && !pearlDone) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl p && predicted == null && p.tickCount >= 1) {
                    predicted = dev.prismglass.module.render.PearlPredict.simulate(p, 400);
                    predictedAtTick = p.tickCount;
                }
            }
            var server = mc.getSingleplayerServer();
            var lvl = server.overworld();
            var list = lvl.getEntities(net.minecraft.world.entity.EntityType.ENDER_PEARL, e -> e.entityTags().contains("prismtest"));
            if (!list.isEmpty()) { seenServer = true; lastServerPos = list.get(0).position(); lastServerAge = list.get(0).tickCount; }
            else if (seenServer && predicted != null) {
                pearlDone = true;
                log(String.format("pearl: predicted teleport %s after %d more ticks (from age %d); server's last position %s at age %d; error %.3f blocks",
                    fmt(predicted.teleport()), predicted.ticks(), predictedAtTick, fmt(lastServerPos), lastServerAge,
                    predicted.teleport() == null ? -1 : predicted.teleport().distanceTo(lastServerPos)));
            }
        }
        if (pearlDone && t > 120) {
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }
}
