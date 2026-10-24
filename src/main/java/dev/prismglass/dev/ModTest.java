package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only checks for the modules added from the spec list (./gradlew runClient -Pmodtest [-Pmodcases=a,b]):
 * a survival flat world, then one case at a time: setup (commands, enable the module), wait until the check passes
 * or the case times out, clean up. Checks read the integrated server where possible (what the server really saw).
 */
public final class ModTest {
    private static final Minecraft mc = Minecraft.getInstance();

    /** One case: setup runs once, check is polled each tick until true or timeout, cleanup always runs. */
    record Case(String name, int timeout, Runnable setup, BooleanSupplier check, Runnable cleanup) {}

    private static final List<Case> cases = new ArrayList<>();
    private static int ticks, t, index = -1, failures, passes, settle;
    /** Ticks since the running case started. */
    static int caseTicks;
    private static boolean worldRequested;
    static int px, py, pz;

    private ModTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    static void log(String s) { Prism.LOG.info("[modtest] {}", s); }

    static MinecraftServer server() { return mc.getSingleplayerServer(); }

    static ServerLevel level() { return server().overworld(); }

    static ServerPlayer splayer() { return server().getPlayerList().getPlayers().get(0); }

    /** Runs a command on the server thread, as the server, positioned at the player. */
    static void cmd(String c) {
        MinecraftServer s = server();
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withPosition(splayer().position()), c));
    }

    static String f(String fmt, Object... args) { return String.format(Locale.ROOT, fmt, args); }

    static <T extends Module> T enable(Class<T> type) {
        T m = Prism.modules().get(type);
        m.setEnabled(true);
        return m;
    }

    static void disable(Class<? extends Module> type) { Prism.modules().get(type).setEnabled(false); }

    /** The server-side copy of the first entity of this type near the player, or null. */
    static <E extends Entity> E serverEntity(Class<E> type) {
        for (Entity e : level().getAllEntities()) if (type.isInstance(e) && e.distanceToSqr(splayer()) < 400) return type.cast(e);
        return null;
    }

    private static void start(int i) {
        if (i >= cases.size()) {
            log(f("%d passed, %d failed", passes, failures));
            log(failures == 0 ? "PASS" : "FAILURES " + failures);
            Runtime.getRuntime().halt(failures == 0 ? 0 : 2);
        }
        Case c = cases.get(i);
        // back to the start spot, facing south, empty inventory
        mc.gui.setScreen(null);
        mc.player.setYRot(0);
        mc.player.setXRot(0);
        cmd(f("tp @p %.1f %d %.1f 0 0", (px) + 0.5, py, (pz) + 0.5));
        cmd("clear @p");
        log("---- " + c.name());
        caseTicks = 0;
        try { c.setup().run(); } catch (Throwable e) { log("FAIL " + c.name() + " setup threw " + e); caseTicks = c.timeout(); }
    }

    static void add(String name, int timeout, Runnable setup, BooleanSupplier check, Runnable cleanup) {
        cases.add(new Case(name, timeout, setup, check, cleanup));
    }

    private static void tick() {
        ticks++;
        if (!worldRequested && ticks == 80 && mc.level == null) {
            worldRequested = true;
            String name = "prismtest-mods-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(), r -> r.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || server() == null) return;
        t++;
        mc.options.pauseOnLostFocus = false;
        if (t == 30) {
            cmd("gamerule advance_time false");
            cmd("gamerule spawn_mobs false");
            cmd("time set noon");
            cmd("kill @e[type=!player]");
            for (Module m : Prism.modules().all()) if (m.isEnabled() && m.getCategory() != dev.prismglass.module.Category.CLIENT && !(m instanceof dev.prismglass.module.render.Inspect)) m.setEnabled(false);
            px = mc.player.getBlockX(); py = mc.player.getBlockY(); pz = mc.player.getBlockZ();
            ModCases.register();
            String only = System.getProperty("prismglass.modcases", "");
            if (!only.isBlank()) {
                List<String> keep = List.of(only.toLowerCase(Locale.ROOT).split(","));
                cases.removeIf(c -> keep.stream().noneMatch(k -> c.name().toLowerCase(Locale.ROOT).startsWith(k)));
            }
            log(cases.size() + " cases");
        }
        if (t < 50) return;

        if (settle > 0) {
            if (--settle == 0) start(++index);
            return;
        }
        if (index == -1) { start(index = 0); return; }
        Case c = cases.get(index);
        caseTicks++;
        boolean ok = false;
        try { ok = caseTicks > 2 && c.check().getAsBoolean(); } catch (Throwable e) { log("FAIL " + c.name() + " check threw " + e); caseTicks = c.timeout(); }
        if (!ok && caseTicks < c.timeout()) return;
        if (ok) { passes++; log(f("ok   %s (%d ticks)", c.name(), caseTicks)); }
        else { failures++; log("FAIL " + c.name() + " (timed out)"); }
        try { c.cleanup().run(); } catch (Throwable e) { log("cleanup of " + c.name() + " threw " + e); }
        settle = 20; // let cleanup commands land
    }
}
