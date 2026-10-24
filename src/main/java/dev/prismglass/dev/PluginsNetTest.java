package dev.prismglass.dev;

import static dev.prismglass.dev.PluginsTest.*;

import dev.prismglass.Prism;
import dev.prismglass.module.client.AntiCheat;
import dev.prismglass.module.client.Hud;
import dev.prismglass.module.world.PluginScanner;
import dev.prismglass.scan.Confidence;
import dev.prismglass.scan.Finding;
import dev.prismglass.scan.Method;
import dev.prismglass.scan.ScanInputs;
import dev.prismglass.scan.ScanInputs.Reply;
import dev.prismglass.scan.ScanResult;
import dev.prismglass.scan.ScanResult.Visibility;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Network half of -Ppluginstest: the scanner against a real Paper 26.1.2 server running the stub plugins of
 * dev-lab/pluginlab (LuckPerms, WorldEdit, CoreProtect + the HiderLab switch). One client session walks through
 * four server setups, switching them by RCON, and checks what the scanner makes of each:
 * <ol>
 *   <li>open: everything is exposed - verdict OPEN, plugins found by tree, tab and /version names, no sweep, no real command sent;</li>
 *   <li>hidden: tree cut to a whitelist, "/" completes to nothing, /plugins disguised - verdict HIDDEN, but the plugins are still
 *       named through /version, /help and the letter sweep;</li>
 *   <li>strict: all tab-complete dead, /version disguised too - HIDDEN, and only the opt-in error probe can still name them;</li>
 *   <li>namespaces hidden: like send-namespaced: false - FILTERED.</li>
 * </ol>
 * Needs the lab server and RCON; see dev-lab/pluginlab/README.md. Exits "ALL PASS" or "SOME FAILED".
 */
final class PluginsNetTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int step, stepTick;
    private static int rconPort = 25611;
    private static String rconPass = "prismtest";
    private static int joinTicks;
    /** HiderLab mode per scenario: open, hidden, strict, namespaces hidden, hidden again with the sweep off. */
    private static final int[] MODES = {0, 1, 2, 3, 1};

    private PluginsNetTest() {}

    static void install() {
        String env = System.getenv("PRISM_RCON");
        if (env != null && env.contains(":")) {
            rconPort = Integer.parseInt(env.substring(0, env.indexOf(':')));
            rconPass = env.substring(env.indexOf(':') + 1);
        }
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    private static void next() { step++; stepTick = 0; }

    private static boolean has(ScanResult r, String name, Method m) {
        Finding f = r.find(name);
        return f != null && f.has(m);
    }

    private static boolean atLeast(ScanResult r, String name, Confidence c) {
        Finding f = r.find(name);
        return f != null && f.confidence().ordinal() >= c.ordinal();
    }

    private static boolean sweepRan(PluginScanner s) {
        return s.tabAnswers().stream().anyMatch(a -> a.sent().equals("/l"));
    }

    private static boolean reasonMentions(ScanResult r, String text) {
        return r.view.reasons().stream().anyMatch(x -> x.contains(text));
    }

    private static void tick() {
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        PluginScanner scanner = Prism.modules().get(PluginScanner.class);
        stepTick++;
        joinTicks++;
        if (joinTicks == 5) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof AntiCheat) && !(m instanceof Hud)) m.setEnabled(false);
            for (var st : scanner.getSettings()) st.reset();
            scanner.autoScanOnJoin.set(false);
            scanner.setEnabled(true);
            log("joined " + mc.getCurrentServer().ip + " as " + mc.player.getName().getString() + ", brand " + scanner.brand());
        }
        if (joinTicks < 20) return;
        // step layout per scenario: 3k = switch the server, 3k+1 = wait for the tree to be re-sent and scan, 3k+2 = judge
        int scenario = step / 3;
        switch (step % 3) {
            case 0 -> {
                if (scenario > 4) {
                    rcon(rconPort, rconPass, "hidermode 0"); // leave the lab open for the next run
                    finish();
                    return;
                }
                int mode = MODES[scenario];
                log("== scenario " + scenario + ": hidermode " + mode + " -> " + rcon(rconPort, rconPass, "hidermode " + mode).strip());
                next();
            }
            case 1 -> {
                if (stepTick == 50) {
                    scanner.errorProbe.set(false);
                    scanner.probeCommands.set(false);
                    scanner.tabSweep.parse(scenario == 4 ? "Off" : "Auto");
                    if (scenario == 2) { scanner.errorProbe.set(true); scanner.errorProbeCount.setExact(8); scanner.probeCommands.set(true); }
                    scanner.scan(false);
                    next();
                }
            }
            case 2 -> {
                if (!scanner.busy() && stepTick > 10) {
                    ScanResult r = scanner.recompute();
                    for (ScanInputs.TabAnswer a : scanner.tabAnswers())
                        log("  tab " + a.kind() + " \"" + a.sent() + "\" -> " + a.entries().size() + " " + a.entries().subList(0, Math.min(12, a.entries().size())));
                    log("  command answers " + scanner.commandAnswers());
                    dump(r);
                    judge(scenario, scanner, r);
                    check("still connected after scenario " + scenario, mc.getConnection() != null && mc.player != null);
                    next();
                }
            }
            default -> { }
        }
    }

    private static void judge(int scenario, PluginScanner scanner, ScanResult r) {
        switch (scenario) {
            case 0 -> {
                check("open: verdict OPEN", r.view.verdict() == Visibility.OPEN);
                check("open: nothing to explain", r.view.reasons().isEmpty());
                check("open: Paper identified from the brand", r.software.stream().anyMatch(f -> f.name().equals("Paper") && f.has(Method.BRAND)));
                for (String p : List.of("LuckPerms", "WorldEdit", "CoreProtect")) {
                    check("open: " + p + " found at least high", atLeast(r, p, Confidence.HIGH));
                    check("open: " + p + " by its namespaced command in the tree", has(r, p, Method.TREE_NS));
                    check("open: " + p + " by the server's own /version completion", has(r, p, Method.VERSION_TAB));
                    check("open: " + p + " confirmed", atLeast(r, p, Confidence.CONFIRMED));
                }
                check("open: the stub plugin HiderLab is named too", r.find("HiderLab") != null && has(r, "HiderLab", Method.VERSION_TAB));
                check("open: commands listed with the plugin", r.find("LuckPerms").commands().contains("lp"));
                check("open: no alphabet sweep needed", !sweepRan(scanner));
                check("open: no real command was sent", scanner.commandAnswers().isEmpty());
                check("open: help topics seen", scanner.tabAnswers().stream().anyMatch(a -> a.sent().equals("/help ") && a.entries().contains("Aliases")));
                check("open: the Bukkit info commands are not 'custom'", !r.customCommands.contains("plugins") && !r.customCommands.contains("pl"));
            }
            case 1 -> {
                check("hidden: verdict HIDDEN", r.view.verdict() == Visibility.HIDDEN);
                check("hidden: says the tree has no namespaced commands", reasonMentions(r, "no namespaced commands"));
                check("hidden: says /plugins, /version and /about are missing", reasonMentions(r, "are missing"));
                check("hidden: says tab-complete of / is empty", reasonMentions(r, "came back empty"));
                check("hidden: tree no longer lists the plugins", r.tree.namespaced().isEmpty());
                check("hidden: the letter sweep ran (Auto, because / was blocked)", sweepRan(scanner));
                for (String p : List.of("LuckPerms", "WorldEdit", "CoreProtect")) {
                    check("hidden: " + p + " still found through /version names", has(r, p, Method.VERSION_TAB));
                    check("hidden: " + p + " not from the tree", !has(r, p, Method.TREE_NS));
                    check("hidden: " + p + " confirmed", atLeast(r, p, Confidence.CONFIRMED));
                }
                check("hidden: ... and the sweep found their namespaced commands", has(r, "LuckPerms", Method.TAB_NS));
                check("hidden: no real command sent without ErrorProbe", scanner.commandAnswers().isEmpty());
            }
            case 2 -> {
                check("strict: verdict HIDDEN", r.view.verdict() == Visibility.HIDDEN);
                check("strict: /version completions are dead (no name came back)", r.plugins.stream().noneMatch(f -> f.has(Method.VERSION_TAB)));
                check("strict: /version aliases were tried", scanner.tabAnswers().stream().filter(a -> a.kind() == ScanInputs.TabKind.VERSION).count() >= 3);
                check("strict: /plugins answered like an unknown command",
                    scanner.commandAnswers().stream().anyMatch(a -> a.command().equals("plugins") && a.reply() == Reply.UNKNOWN));
                check("strict: the verdict says so", reasonMentions(r, "is answered like an unknown command"));
                for (String p : List.of("LuckPerms", "WorldEdit", "CoreProtect")) {
                    check("strict: " + p + " found by the error probe", has(r, p, Method.ERROR_PROBE));
                    check("strict: " + p + " HIGH", atLeast(r, p, Confidence.HIGH));
                }
                check("strict: commands that do not exist stay unknown",
                    scanner.commandAnswers().stream().anyMatch(a -> a.command().equals("ess") && a.reply() == Reply.UNKNOWN) && r.find("Essentials") == null);
                check("strict: says a plugin exists but is not in the tree", reasonMentions(r, "but is not in the command tree"));
            }
            case 3 -> {
                check("namespaces hidden: verdict FILTERED", r.view.verdict() == Visibility.FILTERED);
                check("namespaces hidden: tab-complete reveals what the tree omits", reasonMentions(r, "tab-complete shows"));
                for (String p : List.of("LuckPerms", "WorldEdit", "CoreProtect")) {
                    check("namespaces hidden: " + p + " found through tab-complete", has(r, p, Method.TAB_NS) && !has(r, p, Method.TREE_NS));
                }
                check("namespaces hidden: no sweep needed (/ still answers)", !sweepRan(scanner));
            }
            case 4 -> {
                check("sweep off: verdict HIDDEN", r.view.verdict() == Visibility.HIDDEN);
                check("sweep off: no alphabet sweep", !sweepRan(scanner));
                for (String p : List.of("LuckPerms", "WorldEdit", "CoreProtect")) {
                    check("sweep off: " + p + " named by /version", has(r, p, Method.VERSION_TAB));
                    check("sweep off: " + p + " namespace confirmed by the \"/" + p.toLowerCase(java.util.Locale.ROOT) + ":\" prefix probe", has(r, p, Method.PREFIX_TAB));
                }
                check("sweep off: the prefix probe lists the commands", r.find("LuckPerms").commands().contains("lp"));
            }
            default -> { }
        }
    }
}
