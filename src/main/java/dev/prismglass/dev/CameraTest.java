package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.render.CameraTweaks;
import dev.prismglass.module.render.Freecam;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.joml.Matrix4f;

/**
 * Dev-only test for CameraTweaks stretch/freelook and Freecam culling (./gradlew runClient -Pcameratest): screenshots
 * with and without stretch, the projection aspect, freelook turning only the camera, and freecam's cull frustum.
 */
public final class CameraTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, inWorld;
    private static boolean requested;
    private static float playerYaw;

    private CameraTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[cameratest] {}", s); }

    private static void shot(String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
    }

    private static double aspect() {
        Matrix4f m = new Matrix4f(dev.prismglass.util.Render3D.lastProjection());
        // perspective: m00 = f / aspect, m11 = f
        return m.m11() / m.m00();
    }

    private static void tick() {
        ticks++;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismcam-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(42L, true, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null) return;
        inWorld++;
        CameraTweaks ct = Prism.modules().get(CameraTweaks.class);
        switch (inWorld) {
            case 100 -> {
                log(String.format("window %dx%d, projection aspect %.3f", mc.getWindow().getWidth(), mc.getWindow().getHeight(), aspect()));
                shot("prism-cam-normal.png");
                ct.stretch.set(true);
                ct.ratio.parse("4:3");
                ct.setEnabled(true);
            }
            case 130 -> {
                log(String.format("stretch 4:3: projection aspect %.3f (want 1.333)", aspect()));
                shot("prism-cam-stretched.png");
                ct.stretch.set(false);
                playerYaw = mc.player.getYRot();
                ct.freelookMode.parse("Toggle");
                ct.freelook.set(com.mojang.blaze3d.platform.InputConstants.KEY_F8);
                dev.prismglass.dev.CameraTest.forceFreelook(ct);
            }
            case 135 -> {
                mc.player.turn(600, 0); // what the mouse does: should turn only the camera
                log("freelooking=" + ct.freelooking() + " third person=" + !mc.options.getCameraType().isFirstPerson());
            }
            case 140 -> {
                float camYaw = mc.gameRenderer.mainCamera().yRot();
                log(String.format("freelook: player yaw %.1f -> %.1f (should stay), camera yaw %.1f (should be ~%.1f)",
                    playerYaw, mc.player.getYRot(), camYaw, playerYaw + 90));
                shot("prism-cam-freelook.png");
                ct.setEnabled(false);
                log("after freelook off: first person again=" + mc.options.getCameraType().isFirstPerson());
                Prism.modules().get(Freecam.class).setEnabled(true);
            }
            case 145 -> mc.player.turn(1200, 0); // freecam turns 180
            case 160 -> {
                var cam = mc.gameRenderer.mainCamera();
                var f = cam.getCullFrustum();
                double rad = Math.toRadians(cam.yRot());
                var ahead = cam.position().add(-Math.sin(rad) * 10, 0, Math.cos(rad) * 10);
                log(String.format("freecam: camera yaw %.1f, player yaw %.1f; frustum at camera: %s, point ahead of the freecam visible: %s",
                    cam.yRot(), mc.player.getYRot(), f != null && Math.abs(f.getCamX() - cam.position().x) < 0.01 && Math.abs(f.getCamZ() - cam.position().z) < 0.01,
                    f != null && f.pointInFrustum(ahead.x, ahead.y, ahead.z)));
                shot("prism-cam-freecam.png");
                Prism.modules().get(Freecam.class).setEnabled(false);
            }
            case 165 -> {
                // APanik mid-panic must not save the panic as the setup
                var fb = Prism.modules().get(dev.prismglass.module.render.Fullbright.class);
                var panik = Prism.modules().get(dev.prismglass.module.client.APanik.class);
                fb.setEnabled(true);
                panik.setEnabled(true);
                try {
                    var m = dev.prismglass.module.client.APanik.class.getDeclaredMethod("panic", String.class);
                    m.setAccessible(true);
                    m.invoke(panik, "TestPlayer");
                } catch (ReflectiveOperationException e) { log("FAIL " + e); }
                log("panic: Fullbright on=" + fb.isEnabled() + " (should be false)");
                Prism.config().save();
                try {
                    String json = java.nio.file.Files.readString(mc.gameDirectory.toPath().resolve("prism/profiles/" + Prism.config().getActive() + ".json"));
                    var o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    var mods = o.has("modules") ? o.getAsJsonObject("modules") : o;
                    log("saved mid-panic: Fullbright enabled=" + mods.getAsJsonObject("Fullbright").get("enabled") + " (should be true)");
                } catch (Exception e) { log("FAIL reading config " + e); }
                panik.setEnabled(false);
                log("APanik off: Fullbright on=" + fb.isEnabled() + " (should be true)");
                fb.setEnabled(false);
            }
            case 170 -> {
                // DangerESP: a crystal next to you (lethal in survival), lit TNT a bit further, a creeper
                var server = mc.getSingleplayerServer();
                var p = mc.player.blockPosition();
                int x = p.getX(), z = p.getZ(), y = 200;
                // open glass platform high up, facing +x: crystal 2 ahead (lethal), creeper 5 ahead-left, lit TNT 7 ahead-right
                String[] cmds = {"fill " + (x - 10) + " " + y + " " + (z - 10) + " " + (x + 10) + " " + y + " " + (z + 10) + " glass",
                    "tp @p " + x + " " + (y + 1) + " " + z + " -90 15", "gamemode survival @p", "difficulty normal",
                    "summon end_crystal " + (x + 2) + " " + (y + 1) + " " + z,
                    "summon creeper " + (x + 5) + " " + (y + 1) + " " + (z - 3) + " {NoAI:1b}",
                    "summon tnt " + (x + 7) + " " + (y + 1) + " " + (z + 3) + " {fuse:600}"};
                for (String c : cmds) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c));
                var d = Prism.modules().get(dev.prismglass.module.render.DangerESP.class);
                d.tracers.parse("All");
                d.setEnabled(true);
            }
            case 200 -> {
                log("DangerESP info: " + Prism.modules().get(dev.prismglass.module.render.DangerESP.class).getInfo() + " (want 1 lethal)");
                shot("prism-cam-danger.png");
            }
            case 215 -> lyrics("SMOKE IT OFF!", 117528);
            case 295 -> {
                logLyrics("normal");
                lyrics("SMOKE IT OFF! - Sped Up", 110927);
            }
            case 375 -> logLyrics("sped up");
            case 380 -> {
                log("completion '.sf find biome_sul': " + Prism.commands().complete(".sf find biome_sul").options());
                Prism.modules().get(dev.prismglass.module.render.Xray.class).seed.set("42");
                Prism.commands().handle(".sf find biome_sulfur_caves");
            }
            case 600 -> {
                log("completion '.sf find biome_sul' (2nd try): " + Prism.commands().complete(".sf find biome_sul").options());
                checkBiome("sulfur_caves");
                Prism.commands().handle(".sf stop");
                Prism.commands().handle(".sf find biome_desert");
            }
            case 800 -> checkBiome("desert");
            case 805 -> {
                Prism.modules().get(dev.prismglass.module.render.DangerESP.class).setEnabled(false);
                Prism.commands().handle(".sf stop");
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
            }
            case 830 -> shot("prism-cam-devprism.png");
            case 840 -> {
                // slime chunks: the module's rule with .seed vs the server's own seed
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                var server = mc.getSingleplayerServer();
                long realSeed = server.overworld().getSeed();
                int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z(), slime = 0, same = 0, n = 0;
                long mine = dev.prismglass.module.render.Xray.parseSeed(Prism.modules().get(dev.prismglass.module.render.Xray.class).seed.get());
                for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 8; z <= cz + 8; z++) {
                    boolean real = net.minecraft.world.level.levelgen.WorldgenRandom.seedSlimeChunk(x, z, realSeed, 987234911L).nextInt(10) == 0;
                    boolean ours = dev.prismglass.module.render.SlimeChunks.isSlimeChunk(mine, x, z);
                    n++;
                    if (real == ours) same++;
                    if (ours) slime++;
                }
                log("slime chunks: " + slime + " of " + n + " around, " + same + "/" + n + " agree with the server");
                Prism.modules().get(dev.prismglass.module.render.SlimeChunks.class).setEnabled(true);
                // lava: a stone wall with a sealed lava block inside, looking at the stone in front of it
                var p = mc.player.blockPosition();
                int x = p.getX(), y = p.getY(), z = p.getZ();
                String[] cmds = {"fill " + (x + 2) + " " + y + " " + (z - 1) + " " + (x + 4) + " " + (y + 2) + " " + (z + 1) + " stone",
                    "setblock " + (x + 3) + " " + (y + 1) + " " + z + " lava", "tp @p " + (x + 0.5) + " " + y + " " + (z + 0.5) + " -90 10"};
                for (String c : cmds) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c));
                var d = Prism.modules().get(dev.prismglass.module.render.DangerESP.class);
                d.lethalOnly.set(false);
                d.setEnabled(true);
            }
            case 880 -> {
                log("lava: DangerESP info " + Prism.modules().get(dev.prismglass.module.render.DangerESP.class).getInfo() + " (want 1 lethal: lava behind), looking at "
                    + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b ? b.getBlockPos() : "nothing"));
                shot("prism-cam-lava.png");
            }
            case 890 -> {
                log("done");
                Runtime.getRuntime().halt(0);
            }
            default -> {}
        }
    }

    /** The find marker's spot, checked against the integrated server's real biome there. */
    private static void checkBiome(String want) {
        var pos = Prism.modules().get(dev.prismglass.module.world.StructureFinder.class).targetPos();
        if (pos == null) { log("biome " + want + ": no marker FAIL"); return; }
        var server = mc.getSingleplayerServer();
        String real = server.submit(() -> server.overworld().getUncachedNoiseBiome(net.minecraft.core.QuartPos.fromBlock(pos.getX()),
                net.minecraft.core.QuartPos.fromBlock(pos.getY()), net.minecraft.core.QuartPos.fromBlock(pos.getZ()))
            .unwrapKey().map(k -> k.identifier().getPath()).orElse("?")).join();
        log("biome " + want + ": marker at " + pos + " (" + Math.round(Math.sqrt(pos.distSqr(mc.player.blockPosition()))) + "m), server says " + real + (real.equals(want) ? " OK" : " MISMATCH"));
    }

    /** NowPlaying's lookup for this song (LRCLIB, then NetEase), as if the player had switched to it. */
    private static void lyrics(String title, long dur) {
        try {
            var np = dev.prismglass.manager.NowPlaying.class;
            var gen = np.getDeclaredField("generation");
            gen.setAccessible(true);
            int g = gen.getInt(null) + 1;
            gen.setInt(null, g);
            var m = np.getDeclaredMethod("fetchLyrics", int.class, String.class, String.class, String.class, long.class);
            m.setAccessible(true);
            m.invoke(null, g, title, "Lumi Athena", "", dur);
        } catch (ReflectiveOperationException e) {
            log("FAIL " + e);
        }
    }

    private static void logLyrics(String what) {
        var l = dev.prismglass.manager.NowPlaying.lyrics;
        long words = l.stream().filter(x -> !x.text().isEmpty()).count();
        log("lyrics " + what + ": " + l.size() + " lines (" + words + " with words), first at " + (l.isEmpty() ? "-" : l.get(0).time() + "ms")
            + ", loading=" + dev.prismglass.manager.NowPlaying.lyricsLoading);
    }

    /** Presses the freelook toggle the way onTick would see it (no real key in a hidden window). */
    static void forceFreelook(CameraTweaks ct) {
        try {
            var m = CameraTweaks.class.getDeclaredMethod("setFreelook", boolean.class);
            m.setAccessible(true);
            m.invoke(ct, true);
        } catch (ReflectiveOperationException e) {
            log("FAIL: " + e);
        }
    }
}
