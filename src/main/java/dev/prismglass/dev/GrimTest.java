package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Dev only: scenario runner against a real Grim server (-Pgrimtest -Pmp=localhost:25600 -Pgrimcases=a,b,c).
 * Each case logs "[grimtest] START name" / "[grimtest] END name"; the orchestrator script counts Grim's console
 * alerts between the two. Waits for "op" (given over RCON by the script) before starting.
 */
public final class GrimTest {
    private static final Minecraft mc = Minecraft.getInstance();

    /**
     * A scenario. {@code prep}: commands run after the arena is cleared and the player is at 0 -60 0 facing +Z;
     * {@code setup} at START; {@code body} every tick until {@code length}; {@code cleanup} at END (modules are
     * switched off after it anyway).
     */
    public record Case(String name, int length, String[] prep, Runnable setup, IntConsumer body, Runnable cleanup) {}

    private static final List<Case> CASES = new ArrayList<>();
    private static int index = -1, t, idle, joinTicks;
    private static boolean announced;
    private static Vec3 startPos;
    private static int startTeleports;

    private GrimTest() {}

    public static void install() {
        GrimCases.register(CASES);
        String only = System.getProperty("prismglass.grimcases", "");
        if (!only.isBlank()) {
            List<String> want = List.of(only.split(","));
            CASES.removeIf(c -> !want.contains(c.name()));
            CASES.sort(java.util.Comparator.comparingInt(c -> want.indexOf(c.name()))); // run in the order given
        }
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    static void log(String s) { Prism.LOG.info("[grimtest] {}", s); }

    /**
     * NCP exempts ops from every check (its bypass permissions are undeclared, and Bukkit gives those to ops), so
     * with -Prconcmds the player stays un-op'd and the orchestrator runs these over RCON as the player.
     */
    static final boolean RCON = Boolean.getBoolean("prismglass.rconcmds");

    static void cmd(String command) {
        if (RCON) log("CMD " + command);
        else mc.getConnection().sendCommand(command);
    }

    static void hold(KeyMapping key, boolean down) { key.setDown(down); }

    static void releaseAll() {
        var o = mc.options;
        for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint, o.keyUse, o.keyAttack}) k.setDown(false);
    }

    static void modulesOff() {
        for (Module m : Prism.modules().all()) {
            if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
        }
    }

    private static void tick() {
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || mc.getConnection() == null) {
            if (index >= 0) { log("DISCONNECTED during " + (index < CASES.size() ? CASES.get(index).name() : "?")); Runtime.getRuntime().halt(0); }
            return;
        }
        if (!announced) { announced = true; log("joined as " + mc.player.getName().getString()); }
        if (index < 0) {
            // wait for op (permission level) from the orchestrator, then a few seconds for chunks
            if (++joinTicks < 100) return;
            if (!RCON && !mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) return;
            modulesOff();
            // switch away and back so the preset's values are re-applied even if the profile already had it
            Prism.anticheat().preset.parse("Custom");
            Prism.anticheat().update();
            Prism.anticheat().preset.parse(System.getProperty("prismglass.acpreset", "Grim"));
            Prism.anticheat().update();
            index = 0;
            t = 0;
        }
        if (index >= CASES.size()) {
            if (++idle == 60) { log("ALL DONE"); Runtime.getRuntime().halt(0); }
            return;
        }
        Case c = CASES.get(index);
        if (t == 0) {
            cmd("gamemode survival");
            cmd("clear @s");
            cmd("effect clear @s");
            cmd("effect give @s saturation infinite 255 true"); // hunger never stops sprinting
            cmd("fill -6 -60 -6 6 -44 60 air");
            cmd("kill @e[type=zombie]");
            cmd("tp @s 0 -60 0 0 0");
            for (String p : c.prep()) cmd(p);
        }
        int start = RCON ? 40 : 20;
        if (t == start) {
            startPos = mc.player.position();
            startTeleports = Prism.serverTeleports;
            log("START " + c.name());
            c.setup().run();
        }
        if (t > start && t <= start + c.length()) c.body().accept(t - start);
        if (t == start + 1 + c.length()) {
            c.cleanup().run();
            releaseAll();
            modulesOff();
            Vec3 d = mc.player.position().subtract(startPos);
            log(String.format("END %s moved %.2f (dx %.2f dy %.2f dz %.2f) at %.2f %.2f %.2f setbacks %d", c.name(), d.horizontalDistance(), d.x, d.y, d.z,
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), Prism.serverTeleports - startTeleports));
        }
        if (t == start + 1 + c.length() + 60) { index++; t = -1; } // let flags settle before the next case
        t++;
    }
}
