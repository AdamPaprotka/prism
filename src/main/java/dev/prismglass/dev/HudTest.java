package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.gui.HudEditorScreen;
import dev.prismglass.module.client.Hud;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Dev only (-Phudtest): flat world, Brackets list format, HUD editor with dragged elements; screenshots, then exits. */
public final class HudTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t;
    private static boolean requested;
    private static String oldLayout, oldFormat;

    private HudTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[hudtest] {}", s); }

    private static void shot(String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismhud-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        Hud hud = Prism.modules().get(Hud.class);
        if (t == 40) {
            oldLayout = hud.layout.get();
            oldFormat = hud.listFormat.get();
            hud.listFormat.parse("Brackets");
            for (Class<? extends dev.prismglass.module.Module> c : java.util.List.of(dev.prismglass.module.world.Timer.class,
                dev.prismglass.module.movement.NoSlow.class, dev.prismglass.module.render.Fullbright.class)) {
                Prism.modules().get(c).setEnabled(true);
            }
            if (mc.screen != null) mc.setScreen(null);
        }
        if (t == 80) shot("prism-hud-brackets.png");
        if (t == 90) mc.setScreen(new HudEditorScreen(null));
        if (t == 110) {
            var b = hud.bounds();
            log("editor elements: " + b.keySet());
            int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
            float[] wm = b.get("Watermark"), list = b.get("ArrayList"), info = b.get("Info");
            // move: watermark to the bottom centre, list to the left, info to the top right
            hud.setPosition("Watermark", sw / 2f - wm[2] / 2, sh - wm[3] - 40, wm[2], wm[3], sw, sh);
            hud.setPosition("ArrayList", 4, 30, list[2], list[3], sw, sh);
            hud.setPosition("Info", sw - info[2] - 4, 4, info[2], info[3], sw, sh);
            log("layout: " + hud.layout.get());
        }
        if (t == 140) shot("prism-hud-editor.png");
        if (t == 150) mc.setScreen(null);
        if (t == 180) {
            shot("prism-hud-moved.png");
            var b = hud.bounds();
            log(String.format("watermark at %.0f,%.0f  list at %.0f,%.0f  info at %.0f,%.0f", b.get("Watermark")[0], b.get("Watermark")[1],
                b.get("ArrayList")[0], b.get("ArrayList")[1], b.get("Info")[0], b.get("Info")[1]));
        }
        if (t == 182) mc.setScreen(new dev.prismglass.gui.AboutScreen(null));
        if (t == 197) shot("prism-about.png");
        if (t == 200) mc.setScreen(new dev.prismglass.gui.ChangelogScreen(null));
        if (t == 215) shot("prism-changelog.png");
        if (t == 222) {
            mc.setScreen(null);
            mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().getCommands().performPrefixedCommand(
                mc.getSingleplayerServer().createCommandSourceStack(), "item replace entity @p weapon.mainhand with diamond_sword"));
            var wp = Prism.modules().get(dev.prismglass.module.render.Waypoints.class);
            wp.add("Base", mc.player.getBlockX() + 6, mc.player.getBlockY(), mc.player.getBlockZ() + 25);
            wp.setEnabled(true);
        }
        if (t == 232) Prism.modules().get(dev.prismglass.module.client.KeyActions.class).actions().get(0).run().run(); // Inspect
        if (t == 238) shot("prism-inspect-1.png");
        if (t == 250) shot("prism-inspect-2.png");
        if (t == 262) { mc.setScreen(new dev.prismglass.gui.ClickGuiScreen()); }
        if (t == 266 && mc.screen instanceof dev.prismglass.gui.ClickGuiScreen g) g.debugMenu(3);
        if (t == 280) shot("prism-keybinds.png");
        if (t == 290) {
            Prism.modules().get(dev.prismglass.module.render.Waypoints.class).remove("Base");
            hud.layout.set(oldLayout);
            hud.listFormat.parse(oldFormat);
            Prism.config().save();
            log("done");
            Runtime.getRuntime().halt(0);
        }
    }
}
