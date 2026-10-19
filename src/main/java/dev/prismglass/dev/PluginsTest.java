package dev.prismglass.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.prismglass.Prism;
import dev.prismglass.module.client.Hud;
import dev.prismglass.module.world.PluginScanner;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev only (-Ppluginstest): PluginScanner against the integrated server dressed up as a Bukkit one. Namespaced
 * commands ("worldedit:wand", ...) are added to the real server dispatcher and the real command tree, tab-complete
 * and /bukkit:plugins packets flow. Checks passive (tree), active (tab), probe (proper names), the ignore list,
 * the .plugins command and the HUD panel. Logs "[pluginstest]" PASS/FAIL lines, saves prism-plugins.png, exits.
 */
public final class PluginsTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t, passed, failed;
    private static boolean requested;

    private PluginsTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[pluginstest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void fakeCommand(MinecraftServer s, String name) {
        s.getCommands().getDispatcher().getRoot().addChild(
            LiteralArgumentBuilder.<CommandSourceStack>literal(name).executes(c -> 1).build());
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismplugins-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        PluginScanner scanner = Prism.modules().get(PluginScanner.class);

        if (t == 30) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof Hud)) m.setEnabled(false);
            run(s, "gamerule advance_time false");
            // the integrated server pretends to be Paper: namespaced copies of plugin commands + /bukkit:plugins
            s.execute(() -> {
                for (String n : List.of("worldedit:wand", "worldedit:schematic", "coreprotect:co", "minecraft:tp", "bukkit:version", "paper:paper"))
                    fakeCommand(s, n);
                s.getCommands().getDispatcher().getRoot().addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("bukkit:plugins")
                    .executes(c -> {
                        c.getSource().sendSystemMessage(Component.literal("§fPlugins (3): §aCoreProtect§f, §aWorldEdit§f, §aLuckPerms"));
                        return 1;
                    }).build());
                s.getCommands().sendCommands(s.getPlayerList().getPlayers().get(0));
            });
        }
        // 1) passive only: the tree the server just sent
        if (t == 50) {
            for (var st : scanner.getSettings()) st.reset();
            scanner.activeScan.set(false);
            scanner.probeCommands.set(false);
            scanner.setEnabled(true); // onEnable schedules a scan 60 ticks later
        }
        if (t == 150) {
            log("passive: " + scanner.plugins() + " from " + scanner.sources() + ", brand " + scanner.brand());
            check("passive finds the two namespaces", scanner.plugins().equals(List.of("coreprotect", "worldedit")));
            check("passive source is the tree only", scanner.sources().equals(java.util.Set.of("tree")));
            check("server core namespaces ignored", !scanner.plugins().contains("minecraft") && !scanner.plugins().contains("bukkit") && !scanner.plugins().contains("paper"));
            check("brand read", scanner.brand() != null && !scanner.brand().isEmpty());
        }
        // 2) active: a command added after the tree was sent only shows up through tab-complete
        if (t == 160) {
            s.execute(() -> fakeCommand(s, "luckperms:lp"));
            scanner.activeScan.set(true);
        }
        if (t == 170) Prism.commands().handle(".plugins");
        if (t == 230) {
            log("active: " + scanner.plugins() + " from " + scanner.sources());
            check("active adds the tab-only plugin", scanner.plugins().equals(List.of("coreprotect", "luckperms", "worldedit")));
            check("active source includes tab", scanner.sources().contains("tab"));
        }
        // 3) probe: /bukkit:plugins gives the real names
        if (t == 240) Prism.commands().handle(".plugins probe");
        if (t == 330) {
            log("probe: " + scanner.plugins() + " from " + scanner.sources());
            check("probe gives proper names", scanner.plugins().equals(List.of("CoreProtect", "LuckPerms", "WorldEdit")));
            check("probe source recorded", scanner.sources().contains("probe"));
            check("ignore list is editable", scanner.ignoreList.add("worldedit") && scanner.ignoreList.items().contains("worldedit"));
        }
        if (t == 340) Prism.commands().handle(".plugins");
        if (t == 400) {
            check("ignored namespace dropped after rescan", scanner.plugins().equals(List.of("coreprotect", "luckperms")));
            scanner.ignoreList.reset();
            scanner.hud.set(true);
            Prism.commands().handle(".plugins last");
            check("tab completion offers probe/last", Prism.commands().complete(".plugins ").options().containsAll(List.of("probe", "last")));
        }
        if (t == 420) {
            check("HUD panel registered with the editor", Prism.modules().get(Hud.class).bounds().containsKey("Plugins"));
            Screenshot.grab(mc.gameDirectory, "prism-plugins.png", mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        }
        if (t == 440) {
            log("passed " + passed + " failed " + failed);
            log(failed == 0 ? "ALL PASS" : "SOME FAILED");
            Runtime.getRuntime().halt(0);
        }
    }
}
