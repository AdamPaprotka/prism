package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.render.Inspect;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only Inspect check (./gradlew runClient -Pinspecttest): runs {@link Inspect#selfCheck()}, then inspects a row
 * of items in a flat world and grabs 6 screenshots across each clip into run/screenshots
 * (inspect-&lt;item&gt;-&lt;n&gt;.png), checks the pose is back at rest after each clip, and exits.
 */
public final class InspectTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final List<String> ITEMS = List.of("diamond_sword", "diamond_sword", "netherite_axe", "diamond_pickaxe", "mace", "trident",
        "bow", "crossbow", "grass_block", "totem_of_undying", "golden_apple", "ender_pearl", "air");
    private static final float[] SHOTS = {0.12f, 0.27f, 0.42f, 0.57f, 0.72f, 0.87f};

    private static int ticks, t, item = -1, shot, failures;
    private static boolean worldRequested;
    private static long startNanos;
    private static float seconds;
    private static String label;

    private InspectTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[inspecttest] {}", s); }

    private static void tick() {
        ticks++;
        if (!worldRequested && ticks == 80 && mc.level == null) {
            worldRequested = true;
            List<String> bad = Inspect.selfCheck();
            for (String b : bad) log("FAIL selfcheck: " + b);
            failures += bad.size();
            log("selfcheck: " + Inspect.CLIPS.size() + " clips, " + bad.size() + " problems");
            String name = "prismtest-inspect-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, WorldOptions.defaultWithRandomSeed(), r -> r.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        t++;
        MinecraftServer server = mc.getSingleplayerServer();
        mc.options.pauseOnLostFocus = false;
        if (t == 30) {
            run(server, "gamerule advance_time false");
            run(server, "time set noon");
            if (mc.gui.hud.isHidden()) mc.gui.hud.toggle(); // F1 hides the first-person hand too
            mc.player.setXRot(10);
        }
        if (t < 60) return;
        Inspect inspect = Prism.modules().get(Inspect.class);

        if (item == -1 || (shot >= SHOTS.length && !inspect.inspecting())) {
            if (item >= 0 && t % 1 == 0) log(String.format(Locale.ROOT, "ok   %s back at rest", label));
            item++;
            if (item >= ITEMS.size()) {
                log(failures == 0 ? "PASS" : "FAILURES " + failures);
                Runtime.getRuntime().halt(failures == 0 ? 0 : 2);
            }
            String id = ITEMS.get(item);
            run(server, "item replace entity @p weapon.mainhand with " + id);
            label = id + (ITEMS.subList(0, item).contains(id) ? "2" : "");
            shot = 0;
            startNanos = -1;
            return;
        }
        if (startNanos < 0) {
            // wait for the item to arrive and the equip animation to settle
            if (!mc.player.getMainHandItem().getItem().toString().contains(ITEMS.get(item)) && !ITEMS.get(item).equals("air")) return;
            if (mc.player.getAttackStrengthScale(0) < 1) return;
            inspect.trigger();
            seconds = inspect.clipSeconds();
            startNanos = System.nanoTime();
            log(String.format(Locale.ROOT, "%s: clip %s, %.1f s", label, inspect.clipName(), seconds));
            return;
        }
        float u = (System.nanoTime() - startNanos) / 1e9f / seconds;
        if (shot < SHOTS.length && u >= SHOTS[shot]) {
            String file = String.format(Locale.ROOT, "inspect-%02d-%s-%d.png", item, label, shot);
            Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
            shot++;
        }
        if (u > 1.6f && inspect.inspecting()) {
            log("FAIL " + label + " still inspecting after its clip ended");
            failures++;
            inspect.cancel();
        }
    }

    private static void run(MinecraftServer server, String cmd) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }
}
