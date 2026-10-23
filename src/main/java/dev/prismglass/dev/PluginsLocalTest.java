package dev.prismglass.dev;

import static dev.prismglass.dev.PluginsTest.*;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.prismglass.Prism;
import dev.prismglass.module.client.AntiCheat;
import dev.prismglass.module.client.Hud;
import dev.prismglass.module.world.PluginScanner;
import dev.prismglass.scan.Confidence;
import dev.prismglass.scan.Finding;
import dev.prismglass.scan.Method;
import dev.prismglass.scan.ScanEngine;
import dev.prismglass.scan.ScanInputs;
import dev.prismglass.scan.ScanInputs.TabAnswer;
import dev.prismglass.scan.ScanInputs.TabKind;
import dev.prismglass.scan.ScanResult;
import dev.prismglass.scan.ScanResult.Visibility;
import dev.prismglass.scan.ServerIntel;
import dev.prismglass.scan.Signature;
import dev.prismglass.scan.SignatureDb;
import dev.prismglass.scan.TreeAnalyzer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Local half of -Ppluginstest: the integrated server dressed up as a Bukkit one. Namespaced fake plugin commands go
 * into the real server dispatcher, so the real command tree, tab-complete, chat and plugin-message/ping packets flow
 * to the client; what the integrated server cannot say (server-list data, a hiding Paper) is fed to the engine
 * directly, and the engine's scoring is also checked on invented trees. Each phase is a scan with other settings, then
 * a judgement. Logs "[pluginstest]" PASS/FAIL lines, saves prism-plugins.png, exits.
 */
final class PluginsLocalTest {
    private PluginsLocalTest() {}

    /** One phase: mutate the server, wait, optionally scan, wait for the scan, judge. */
    private record Phase(String name, Runnable setup, int delay, Runnable go, Consumer<ScanResult> judge) {}

    private static final List<Phase> phases = new ArrayList<>();
    private static int ticks, t, idx, phaseTick;
    private static boolean requested, started, scanned;
    private static net.minecraft.client.Minecraft mc;

    /** Channel ids the fake server pushes. Fabric refuses to encode a custom payload nobody registered, so each gets a type. */
    private static final List<String> CHANNEL_IDS = List.of("voicechat:secret", "luckperms:update", "worldedit:cui", "xaerominimap:main", "mystery:chan", "fabric-test-api-v1:sync");
    private static final Map<String, CustomPacketPayload> CHANNELS = new HashMap<>();

    private record Chan(CustomPacketPayload.Type<Chan> type) implements CustomPacketPayload {}

    static void install() {
        for (String id : CHANNEL_IDS) {
            CustomPacketPayload.Type<Chan> type = new CustomPacketPayload.Type<>(Identifier.parse(id));
            Chan payload = new Chan(type);
            PayloadTypeRegistry.clientboundPlay().register(type, StreamCodec.unit(payload));
            CHANNELS.put(id, payload);
        }
        mc = net.minecraft.client.Minecraft.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    // ---- server helpers ----------------------------------------------------------------------------------

    private static void onServer(Runnable r) { mc.getSingleplayerServer().execute(r); }

    private static void fake(MinecraftServer s, String name) {
        s.getCommands().getDispatcher().getRoot().addChild(LiteralArgumentBuilder.<CommandSourceStack>literal(name).executes(c -> 1).build());
    }

    /** A command that answers with a fixed line, like a plugin's help banner. */
    private static void fakeSays(MinecraftServer s, String name, String line) {
        s.getCommands().getDispatcher().getRoot().addChild(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
            .executes(c -> { c.getSource().sendSystemMessage(Component.literal(line)); return 1; }).build());
    }

    /** "/name <word>" whose completions are fixed, like Bukkit's /version <plugin> and /help <topic>. */
    private static void fakeCompletes(MinecraftServer s, String name, String... words) {
        s.getCommands().getDispatcher().getRoot().addChild(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
            .executes(c -> 1)
            .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("arg", StringArgumentType.greedyString())
                .suggests((c, b) -> { for (String w : words) if (w.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(w); return b.buildFuture(); })
                .executes(c -> 1)).build());
    }

    private static void resendTree() {
        onServer(() -> mc.getSingleplayerServer().getCommands().sendCommands(mc.getSingleplayerServer().getPlayerList().getPlayers().get(0)));
    }

    private static boolean has(ScanResult r, String name, Method m) {
        Finding f = r.find(name);
        return f != null && f.has(m);
    }

    private static int weight(ScanResult r, String name, Method m) {
        Finding f = r.find(name);
        if (f == null) return -1;
        return f.evidence().stream().filter(e -> e.method() == m).mapToInt(e -> e.weight()).max().orElse(-1);
    }

    private static void quiet(PluginScanner sc, boolean tab, boolean version, boolean help, boolean ns, String sweep) {
        sc.activeScan.set(tab);
        sc.versionProbe.set(version);
        sc.helpProbe.set(help);
        sc.namespaceProbe.set(ns);
        sc.tabSweep.parse(sweep);
        sc.errorProbe.set(false);
        sc.probeCommands.set(false);
    }

    // ---- phases --------------------------------------------------------------------------------------------

    private static void define(PluginScanner sc) {
        // 1) passive: only the tree the server sent
        phases.add(new Phase("passive: tree only",
            () -> {
                for (var m : Prism.modules().all())
                    if (m.isEnabled() && !(m instanceof AntiCheat) && !(m instanceof Hud)) m.setEnabled(false);
                for (var st : sc.getSettings()) st.reset();
                sc.autoScanOnJoin.set(false);
                onServer(() -> {
                    MinecraftServer s = mc.getSingleplayerServer();
                    for (String n : List.of("worldedit:wand", "worldedit:schematic", "coreprotect:co", "mycustomplugin:cmd", "minecraft:tp", "bukkit:version", "paper:paper", "bukkit:help"))
                        fake(s, n);
                    fakeSays(s, "bukkit:plugins", "Plugins (3): §aCoreProtect§f, §aLuckPerms§f, §aWorldEdit");
                    fakeSays(s, "plugins", "Plugins (3): §aCoreProtect§f, §aLuckPerms§f, §aWorldEdit");
                    fakeCompletes(s, "version", "CoreProtect", "LuckPerms", "Vault", "WorldEdit");
                    fakeCompletes(s, "help", "Aliases", "Bukkit", "Minecraft", "Essentials", "lp");
                    s.getCommands().sendCommands(s.getPlayerList().getPlayers().get(0));
                });
                sc.setEnabled(true);
            }, 20, () -> { quiet(sc, false, false, false, false, "Off"); sc.scan(false); },
            r -> {
                log("passive: " + sc.plugins());
                check("passive: the namespaces in the tree are found", sc.plugins().equals(List.of("CoreProtect", "mycustomplugin", "WorldEdit")));
                check("passive: database names (CoreProtect, WorldEdit) and the unknown namespace kept as typed",
                    r.find("CoreProtect") != null && r.find("WorldEdit") != null && r.find("mycustomplugin") != null && r.find("mycustomplugin").sig == null);
                check("passive: tree evidence only", r.plugins.stream().allMatch(f -> f.methods().equals(List.of(Method.TREE_NS))));
                check("passive: a namespace in the tree is a high confidence", r.find("WorldEdit").confidence() == Confidence.HIGH);
                check("passive: weight of a tree namespace is 90", weight(r, "WorldEdit", Method.TREE_NS) == 90);
                check("passive: server core namespaces ignored", r.find("minecraft") == null && r.find("bukkit") == null && r.find("paper") == null);
                check("passive: nothing was asked", sc.tabAnswers().isEmpty() && sc.commandAnswers().isEmpty());
                check("passive: brand read", sc.brand() != null && !sc.brand().isEmpty());
                check("passive: commands look open", r.view.verdict() == Visibility.OPEN && r.view.bukkitLike());
                check("passive: the wand command is listed for the hover", r.find("WorldEdit").commands().contains("wand"));
            }));

        // 2) active: tab-complete "/" finds a command added after the tree went out
        phases.add(new Phase("active: tab-complete of /",
            () -> onServer(() -> fake(mc.getSingleplayerServer(), "luckperms:lp")), 10,
            () -> { quiet(sc, true, false, false, false, "Off"); sc.scan(false); },
            r -> {
                log("active: " + sc.plugins());
                check("active: finds the tab-only plugin", r.find("LuckPerms") != null && has(r, "LuckPerms", Method.TAB_NS) && !has(r, "LuckPerms", Method.TREE_NS));
                check("active: asked exactly once", sc.tabAnswers().size() == 1 && sc.tabAnswers().get(0).sent().equals("/"));
                check("active: the verdict notes what the tree left out",
                    r.view.reasons().stream().anyMatch(x -> x.contains("tab-complete shows 1 namespaced")) && r.view.verdict() == Visibility.OPEN);
                check("active: the same fact seen twice does not add up (tree+tab = family cap)", r.find("WorldEdit").score() == 90);
            }));

        // 3) the plugin names the server itself completes
        phases.add(new Phase("names: /version and /help tab-complete", () -> { }, 1,
            () -> { quiet(sc, true, true, true, false, "Off"); sc.scan(false); },
            r -> {
                log("names: " + sc.plugins());
                check("names: /version names confirm", has(r, "LuckPerms", Method.VERSION_TAB) && r.find("LuckPerms").confidence() == Confidence.CONFIRMED);
                check("names: a library with no commands is named", r.find("Vault") != null && r.find("Vault").confidence() == Confidence.CONFIRMED && r.find("Vault").kind().equals("library"));
                check("names: /help topic of a known plugin counts, weaker", weight(r, "Essentials", Method.HELP_TAB) == 70 && r.find("Essentials").confidence() == Confidence.MEDIUM);
                check("names: general help topics are not plugins", r.find("Aliases") == null && r.find("Bukkit") == null && r.find("Minecraft") == null);
                check("names: a command's help topic (lowercase) is not a plugin", r.find("lp") == null);
                check("names: tree+tab+name still just one confirmed plugin entry", r.plugins.stream().filter(f -> f.name().equals("WorldEdit")).count() == 1);
            }));

        // 4) namespace prefix probe for plugins known only by name
        phases.add(new Phase("prefix: /plugin: probe for name-only plugins",
            () -> onServer(() -> fake(mc.getSingleplayerServer(), "vault:vault-info")), 10,
            () -> { quiet(sc, false, true, false, true, "Off"); sc.scan(false); },
            r -> {
                log("prefix: " + sc.plugins());
                check("prefix: name-only Vault was probed", sc.tabAnswers().stream().anyMatch(a -> a.sent().equals("/vault:") && a.kind() == TabKind.PREFIX));
                check("prefix: the prefix lists its command and confirms the namespace", has(r, "Vault", Method.PREFIX_TAB) && r.find("Vault").commands().contains("vault-info"));
                check("prefix: LuckPerms was probed too", has(r, "LuckPerms", Method.PREFIX_TAB));
                check("prefix: the /bukkit: alias was not asked (the tree has namespaces)", sc.tabAnswers().stream().noneMatch(a -> a.sent().equals("/bukkit:")));
            }));

        // 5) letter sweep
        phases.add(new Phase("sweep: always", () -> { }, 1,
            () -> { quiet(sc, false, false, false, false, "Always"); sc.scan(false); },
            r -> {
                check("sweep: 26 letters asked", sc.tabAnswers().stream().filter(a -> a.sent().matches("/[a-z]")).count() == 26);
                check("sweep: the letters find namespaced commands", has(r, "CoreProtect", Method.TAB_NS) && has(r, "WorldEdit", Method.TAB_NS));
                check("sweep: asked one at a time with a gap (no burst)", sc.tabAnswers().size() == 26);
            }));

        // 6) the plugin list command
        phases.add(new Phase("probe: /bukkit:plugins", () -> { }, 1,
            () -> { quiet(sc, true, false, false, false, "Off"); sc.scan(true); },
            r -> {
                log("probe: " + sc.plugins());
                check("probe: the server's own list is parsed (colour codes stripped)", has(r, "CoreProtect", Method.PLUGINS_CMD) && has(r, "LuckPerms", Method.PLUGINS_CMD));
                check("probe: confirmed", r.find("LuckPerms").confidence() == Confidence.CONFIRMED);
                check("probe: parser reads the Bukkit one-liner", PluginScanner.parsePluginList("§fPlugins (3): §aA§f, §aB§f, §aC").equals(List.of("A", "B", "C")));
                check("probe: parser reads the Paper multi-line list", PluginScanner.parsePluginList("Server Plugins (3):\nBukkit Plugins:\n - A, B\nPaper Plugins:\n - C").equals(List.of("A", "B", "C")));
                check("probe: parser ignores refusals", PluginScanner.parsePluginList("Unknown command. Type \"/help\" for help.").isEmpty());
                check("probe: ignore list is editable", sc.ignoreList.add("worldedit") && sc.ignoreList.items().contains("worldedit"));
            }));
        phases.add(new Phase("ignore list", () -> { }, 1,
            () -> { quiet(sc, true, false, false, false, "Off"); sc.scan(false); },
            r -> {
                check("ignore: the ignored namespace is gone", r.find("WorldEdit") == null && sc.plugins().stream().noneMatch(n -> n.equals("WorldEdit")));
                sc.ignoreList.reset();
            }));

        // 7) error probe: a made-up command vs real ones
        phases.add(new Phase("error probe: unknown vs answered", () -> onServer(() -> {
                MinecraftServer s = mc.getSingleplayerServer(); // runnable but never sent in the tree: like a hidden command
                fakeSays(s, "lp", "LuckPerms v5.4 (fake) - usage: /lp help");
                fakeSays(s, "we", "WorldEdit version 7 (fake) - usage: //help");
            }), 5,
            () -> { quiet(sc, false, false, false, false, "Off"); sc.errorProbe.set(true); sc.scan(false); },
            r -> {
                log("error probe: " + sc.commandAnswers());
                Map<String, ScanInputs.Reply> a = new HashMap<>();
                sc.commandAnswers().forEach(c -> a.put(c.command(), c.reply()));
                check("error probe: /lp is answered", a.get("lp") == ScanInputs.Reply.EXISTS);
                check("error probe: /we is answered", a.get("we") == ScanInputs.Reply.EXISTS);
                check("error probe: /ess does not exist", a.get("ess") == ScanInputs.Reply.UNKNOWN);
                check("error probe: /plugins answers with the list", a.get("plugins") == ScanInputs.Reply.EXISTS);
                check("error probe: LuckPerms gets high evidence from it", weight(r, "LuckPerms", Method.ERROR_PROBE) == 85);
                check("error probe: Essentials stays absent", r.find("Essentials") == null);
                check("error probe: at most the baseline + /plugins + 5 probes", sc.commandAnswers().size() <= 6);
            }));

        // 8) pings (anticheat transactions), channels, tree re-sends: real packets from the integrated server
        phases.add(new Phase("pings: grim-style transactions", () -> {
                ServerIntel.reset();
                onServer(() -> { ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0); for (int i = 1; i <= 24; i++) p.connection.send(new ClientboundPingPacket(-i)); });
            }, 10, null,
            r -> {
                ScanResult now = sc.recompute();
                log("ping: " + now.ping);
                check("pings: ids stepping down by one are Grim's scheme", now.ping.kind().equals("grim") && now.ping.total() == 24);
                check("pings: GrimAC found, medium", now.find("GrimAC") != null && weight(now, "GrimAC", Method.PING) == 70 && now.find("GrimAC").confidence() == Confidence.MEDIUM);
                check("pings: listed under anticheats", now.anticheats.stream().anyMatch(f -> f.name().equals("GrimAC")));
            }));
        phases.add(new Phase("pings: other transaction anticheats", () -> {
                ServerIntel.reset();
                onServer(() -> { ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0); for (int id : new int[]{-5, -900, -17, -3000, -2, -88, -4000, -31}) p.connection.send(new ClientboundPingPacket(id)); });
            }, 10, null,
            r -> {
                ScanResult now = sc.recompute();
                check("pings: negative but not stepping = a transaction anticheat, not named", now.ping.kind().equals("negative") && now.find("GrimAC") == null
                    && now.find("Transaction anticheat") != null && weight(now, "Transaction anticheat", Method.PING) == 55);
                ServerIntel.reset();
                onServer(() -> { ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0); for (int id : new int[]{1000, 99999, 5, 123456, 77, 1000000, 31, 4242}) p.connection.send(new ClientboundPingPacket(id)); });
            }));
        phases.add(new Phase("pings: any ping stream", () -> { }, 10, null,
            r -> {
                ScanResult now = sc.recompute();
                check("pings: positive ids = a stream (vanilla and Paper send none)", now.ping.kind().equals("stream") && weight(now, "Transaction anticheat", Method.PING) == 45
                    && now.find("Transaction anticheat").confidence() == Confidence.LOW);
                ServerIntel.reset();
                check("pings: too few pings say nothing", sc.recompute().ping.kind().equals("none"));
            }));
        phases.add(new Phase("channels: plugin-message ids", () -> {
                ServerIntel.reset();
                onServer(() -> {
                    ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                    for (String id : CHANNEL_IDS) p.connection.send(new ClientboundCustomPayloadPacket(CHANNELS.get(id)));
                });
            }, 10, null,
            r -> {
                ScanResult now = sc.recompute();
                log("channels: " + ServerIntel.snapshot().channels());
                check("channels: an exact channel is strong", weight(now, "WorldEdit", Method.CHANNEL) == 85);
                check("channels: a prefix channel is weaker", weight(now, "Simple Voice Chat", Method.CHANNEL) == 70 && weight(now, "LuckPerms", Method.CHANNEL) == 70);
                check("channels: client-integration entries are listed as plugins", now.find("Xaero's map rules") != null);
                check("channels: an unknown channel is reported, not guessed", now.otherChannels.contains("mystery:chan") && !now.otherChannels.contains("worldedit:cui"));
                check("channels: a server-side mod channel is software, not a plugin", now.software.stream().anyMatch(f -> f.name().startsWith("Fabric API")) && now.find("Fabric API (server side)") != null
                    && now.plugins.stream().noneMatch(f -> f.name().startsWith("Fabric API")));
                check("channels: counted once each", ServerIntel.snapshot().channels().stream().filter(c -> List.of("voicechat:secret", "worldedit:cui", "mystery:chan").contains(c.id())).allMatch(c -> c.count() == 1));
                ServerIntel.reset();
                // the tree being re-sent three times in a row is a sign of permission/command-filter plugins
                resendTree();
                resendTree();
                resendTree();
            }));
        phases.add(new Phase("tree re-sent", () -> { }, 15, null,
            r -> {
                ScanResult now = sc.recompute();
                check("tree: the re-sends were counted", ServerIntel.snapshot().treeCount() >= 3);
                check("tree: the verdict mentions them", now.view.reasons().stream().anyMatch(x -> x.contains("re-sent")));
            }));

        // 9) synthetic server list data, brand and resource packs
        phases.add(new Phase("status: server list, brand, resource pack (synthetic)", () -> { }, 1, null,
            r -> {
                SignatureDb db = PluginScanner.signatures();
                ScanInputs base = ScanInputs.empty();
                ScanInputs.StatusInfo st = new ScanInputs.StatusInfo("Paper 1.21.4", 0, "26.1.2", 0, false);
                ScanResult a = ScanEngine.analyze(withStatus(base, st, "Paper"), db);
                check("status: Paper recognised from the brand and the server list", a.software.stream().anyMatch(f -> f.name().equals("Paper") && f.has(Method.BRAND) && f.has(Method.STATUS)));
                check("status: an older version in the server list means version translation", a.find("Version translation") != null && weight(a, "Version translation", Method.STATUS) == 60);
                ScanResult b = ScanEngine.analyze(withStatus(base, new ScanInputs.StatusInfo("Paper 1.21.4", 0, "26.1.2", 0, true), "Paper"), db);
                check("status: not when this client translates versions itself", b.find("Version translation") == null);
                ScanResult c = ScanEngine.analyze(withStatus(base, new ScanInputs.StatusInfo("Paper 26.1.2", 0, "26.1.2", 0, false), "Paper"), db);
                check("status: nor when the versions match", c.find("Version translation") == null);
                ScanResult d = ScanEngine.analyze(withStatus(base, st, "Paper (Velocity)"), db);
                check("status: a proxy brand is a second finding", d.software.stream().anyMatch(f -> f.name().equals("Velocity")) && d.software.stream().anyMatch(f -> f.name().equals("Paper")));
                ServerIntel.Snapshot withPack = new ServerIntel.Snapshot(1, List.of(), List.of(), 0, List.of(new ServerIntel.Pack(ServerIntel.hostOf("https://atlas.oraxen.com/pack.zip?x=1"), false)), 0, 0, 0, null, false);
                ScanResult e = ScanEngine.analyze(new ScanInputs(TreeAnalyzer.Report.empty(), List.of(), withPack, null, ScanInputs.StatusInfo.none(), List.of(), List.of(), false, s -> false), db);
                check("status: a resource pack host hints at the plugin that serves it (low)", e.find("Oraxen") != null && weight(e, "Oraxen", Method.PACK) == 40 && e.find("Oraxen").confidence() == Confidence.LOW);
                check("status: the pack host is read without the path or query", ServerIntel.hostOf("https://Atlas.Oraxen.com:8080/a/b?token=x").equals("atlas.oraxen.com") && ServerIntel.hostOf("not a url").equals("?"));
            }));

        // 10) the scoring and the verdict on invented servers (pure engine)
        phases.add(new Phase("engine: verdicts and scoring (synthetic)", () -> { }, 1, null, r -> engineChecks()));

        // 11) database: files, user override, lint
        phases.add(new Phase("database: bundled lint, user file override, reload", () -> { }, 1, null, r -> databaseChecks()));

        // 12) command, tab completion, HUD
        phases.add(new Phase("command + HUD", () -> {
                ServerIntel.reset();
                quiet(sc, true, true, true, false, "Off");
                sc.hud.set(true);
            }, 5, () -> Prism.commands().handle(".plugins"),
            r -> {
                check("command: .plugins scans (and the scan finished)", !sc.busy() && !sc.plugins().isEmpty());
                check("command: tab completion offers probe, deep, last, why, reload",
                    Prism.commands().complete(".plugins ").options().containsAll(List.of("probe", "deep", "last", "why", "reload")));
                check("command: .plugins why <name> completes plugin names", Prism.commands().complete(".plugins why ").options().contains("CoreProtect"));
                Prism.commands().handle(".plugins why");
                Prism.commands().handle(".plugins why CoreProtect");
                Prism.commands().handle(".plugins last");
                Prism.commands().handle(".plugins reload");
                check("command: the aliases exist", Prism.commands().complete(".pl") != null);
                check("HUD: the panel is registered with the HUD editor", hudHasPanel());
                Screenshot.grab(mc.gameDirectory, "prism-plugins.png", mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
            }));
    }

    private static ScanInputs withStatus(ScanInputs base, ScanInputs.StatusInfo st, String brand) {
        return new ScanInputs(base.tree(), base.tabs(), base.intel(), brand, st, base.commandProbes(), base.pluginsReply(), false, s -> false);
    }

    // ---- pure engine checks -----------------------------------------------------------------------------

    private static TreeAnalyzer.Root bukkit(String name) { return new TreeAnalyzer.Root(name, true, List.of(), true, "args:String"); }
    private static TreeAnalyzer.Root vanilla(String name) { return new TreeAnalyzer.Root(name, false, List.of("run"), false, "x:Entity"); }
    private static TreeAnalyzer.Root shaped(String name, String... subs) { return new TreeAnalyzer.Root(name, true, List.of(subs), false, ""); }

    private static ScanInputs tree(String brand, List<TabAnswer> tabs, TreeAnalyzer.Root... roots) {
        return new ScanInputs(new TreeAnalyzer.Report(List.of(roots), roots.length), tabs, ServerIntel.Snapshot.empty(), brand,
            ScanInputs.StatusInfo.none(), List.of(), List.of(), false, s -> false);
    }

    private static TreeAnalyzer.Root[] plainPaper() {
        List<TreeAnalyzer.Root> l = new ArrayList<>();
        for (String n : List.of("help", "?", "plugins", "pl", "version", "ver", "about", "me", "msg", "tell", "w", "teammsg", "tm", "trigger",
            "bukkit:help", "bukkit:?", "bukkit:plugins", "bukkit:pl", "bukkit:version", "bukkit:ver", "bukkit:about", "minecraft:me", "minecraft:msg", "minecraft:tell"))
            l.add(bukkit(n));
        return l.toArray(new TreeAnalyzer.Root[0]);
    }

    private static void engineChecks() {
        SignatureDb db = PluginScanner.signatures();
        List<TabAnswer> noTabs = List.of();

        ScanResult plain = ScanEngine.analyze(tree("Paper", noTabs, plainPaper()), db);
        check("engine: plain Paper, no plugins -> OPEN and nothing listed", plain.view.verdict() == Visibility.OPEN && plain.plugins.isEmpty() && plain.view.reasons().isEmpty());
        check("engine: plain Paper -> Paper recognised", plain.software.stream().anyMatch(f -> f.name().equals("Paper")));

        List<TreeAnalyzer.Root> cut = new ArrayList<>(List.of(bukkit("help"), bukkit("me"), bukkit("msg"), bukkit("tell")));
        ScanResult hidden = ScanEngine.analyze(tree("Paper", List.of(new TabAnswer("/", TabKind.ROOT, false, 0, 0, List.of())), cut.toArray(new TreeAnalyzer.Root[0])), db);
        check("engine: everything cut away and '/' silent -> HIDDEN, not 'no plugins'", hidden.view.verdict() == Visibility.HIDDEN && hidden.plugins.isEmpty() && hidden.view.score() >= 5);
        check("engine: ... with reasons to show", hidden.view.reasons().size() >= 3);

        ScanResult vanilla = ScanEngine.analyze(tree("vanilla", noTabs, vanilla("give"), vanilla("tp"), vanilla("time")), db);
        check("engine: a vanilla server has no plugin layer", vanilla.view.verdict() == Visibility.NONE && vanilla.plugins.isEmpty());
        ScanResult spoof = ScanEngine.analyze(tree("vanilla", noTabs, plainPaper()), db);
        check("engine: brand 'vanilla' with Bukkit command nodes is called out", spoof.view.reasons().stream().anyMatch(x -> x.contains("spoofed")) && spoof.view.bukkitLike());
        ScanResult empty = ScanEngine.analyze(ScanInputs.empty(), db);
        check("engine: no data is UNKNOWN", empty.view.verdict() == Visibility.UNKNOWN);

        // command names: a distinctive name is a medium sign, weak names alone stay low, one weak name is nothing
        ScanResult ess = ScanEngine.analyze(tree("Paper", noTabs, bukkit("ess"), bukkit("sethome"), bukkit("tpa"), bukkit("help"), bukkit("plugins"), bukkit("bukkit:help")), db);
        check("engine: /ess is a strong Essentials sign", ess.find("Essentials") != null && ess.find("Essentials").has(Method.COMMAND) && ess.find("Essentials").confidence() == Confidence.MEDIUM);
        ScanResult weak2 = ScanEngine.analyze(tree("Paper", noTabs, bukkit("home"), bukkit("warp"), bukkit("help"), bukkit("plugins"), bukkit("bukkit:help")), db);
        check("engine: two weak names = a low guess", weak2.find("Essentials") != null && weak2.find("Essentials").confidence() == Confidence.LOW);
        ScanResult weak1 = ScanEngine.analyze(tree("Paper", noTabs, bukkit("home"), bukkit("help"), bukkit("plugins"), bukkit("bukkit:help")), db);
        check("engine: one weak name = nothing, but the command is listed as custom", weak1.find("Essentials") == null && weak1.customCommands.contains("home"));

        // namespace + command names -> high; plus the server's own name list -> confirmed
        TabAnswer names = new TabAnswer("/version ", TabKind.VERSION, true, 9, 0, List.of("LuckPerms"));
        ScanResult lp = ScanEngine.analyze(tree("Paper", List.of(names), bukkit("luckperms:lp"), bukkit("lp"), bukkit("help"), bukkit("plugins"), bukkit("bukkit:help")), db);
        check("engine: namespace + command + server's name = confirmed", lp.find("LuckPerms").confidence() == Confidence.CONFIRMED);
        ScanResult lp2 = ScanEngine.analyze(tree("Paper", noTabs, bukkit("luckperms:lp"), bukkit("lp"), bukkit("help"), bukkit("plugins"), bukkit("bukkit:help")), db);
        check("engine: namespace + command = high (two views of one registration do not make it confirmed)",
            lp2.find("LuckPerms").confidence() == Confidence.HIGH && lp2.find("LuckPerms").score() < 97);

        // structure: a renamed command is still recognised
        ScanResult via = ScanEngine.analyze(tree("Paper", noTabs, shaped("xv", "list", "dump", "dontbugme", "autoteam", "reload"), bukkit("help"), bukkit("plugins"), bukkit("bukkit:help")), db);
        check("engine: a renamed ViaVersion command is found by its sub-commands", via.find("ViaVersion") != null && has(via, "ViaVersion", Method.SHAPE) && !has(via, "ViaVersion", Method.COMMAND));
        check("engine: ... and the evidence says it is not under its usual name", via.find("ViaVersion").evidence().get(0).detail().contains("not its usual name"));

        // the made-up-command probe
        ScanInputs probed = new ScanInputs(new TreeAnalyzer.Report(List.of(plainPaper()), 24), noTabs, ServerIntel.Snapshot.empty(), "Paper", ScanInputs.StatusInfo.none(),
            List.of(new ScanInputs.CommandAnswer("plugins", ScanInputs.Reply.UNKNOWN), new ScanInputs.CommandAnswer("lp", ScanInputs.Reply.DENIED), new ScanInputs.CommandAnswer("we", ScanInputs.Reply.SILENT),
                new ScanInputs.CommandAnswer("co", ScanInputs.Reply.UNKNOWN)), List.of(), false, s -> false);
        ScanResult pr = ScanEngine.analyze(probed, db);
        check("engine: a refusal proves the command exists (high)", pr.find("LuckPerms") != null && pr.find("LuckPerms").confidence() == Confidence.HIGH);
        check("engine: a swallowed answer is only a hint", pr.find("WorldEdit") != null && weight2(pr, "WorldEdit") == 25 && pr.find("WorldEdit").confidence() == Confidence.LOW);
        check("engine: an unknown answer adds nothing", pr.find("CoreProtect") == null);
        check("engine: /plugins answered like an unknown command on a Bukkit server is called out", pr.view.reasons().stream().anyMatch(x -> x.contains("answered like an unknown command")));
    }

    private static int weight2(ScanResult r, String name) { return weight(r, name, Method.ERROR_PROBE); }

    // ---- database ---------------------------------------------------------------------------------------------

    private static void databaseChecks() {
        SignatureDb db = PluginScanner.signatures();
        check("database: loaded without problems", db.problems().isEmpty() && db.bundledCount() >= 90);
        Set<String> seenNs = new HashSet<>();
        List<String> dupNs = new ArrayList<>(), standardCmds = new ArrayList<>(), badPing = new ArrayList<>();
        for (Signature s : db.all()) {
            for (String ns : s.ns()) if (!seenNs.add(ns)) dupNs.add(ns);
            for (String c : s.cmds()) if (TreeAnalyzer.isStandard(c)) standardCmds.add(s.id() + ":" + c);
            for (String c : s.weak()) if (TreeAnalyzer.isStandard(c)) standardCmds.add(s.id() + ":" + c);
            if (s.ping() != null && !s.ping().equals("grim") && !s.ping().equals("transaction")) badPing.add(s.id());
        }
        check("database: no namespace claimed by two entries " + dupNs, dupNs.isEmpty());
        check("database: no command that vanilla or Bukkit already own " + standardCmds, standardCmds.isEmpty());
        check("database: ping kinds are known " + badPing, badPing.isEmpty());
        check("database: the anticheats the task names are there",
            List.of("grimac", "nocheatplus", "vulcan", "matrix", "intave", "polar", "verus", "spartan").stream().allMatch(id -> db.byId(id) != null && db.byId(id).kind().equals("anticheat")));
        check("database: Essentials, WorldGuard, LuckPerms, ViaVersion/ViaBackwards, Geyser/Floodgate are there",
            List.of("essentials", "worldguard", "luckperms", "viaversion", "viabackwards", "geyser", "floodgate").stream().allMatch(id -> db.byId(id) != null));
        check("database: spark is flagged as bundled with the server", db.byId("spark").builtin());

        // an extra entry merged at runtime
        SignatureDb extended = db.with("{'signatures': [{'id': 'mycustom', 'name': 'MyCustom', 'kind': 'plugin', 'ns': ['mycustomplugin'], 'cmds': ['mcp-admin']}], 'remove': ['spark']}");
        check("database: extra entries merge and 'remove' drops one", extended.byNamespace("mycustomplugin") != null && extended.byId("spark") == null && extended.size() == db.size());
        ScanResult r = ScanEngine.analyze(tree("Paper", List.of(), bukkit("mycustomplugin:cmd"), bukkit("mcp-admin"), bukkit("help"), bukkit("plugins"), bukkit("bukkit:help")), extended);
        check("database: a user entry names the plugin and matches its commands", r.find("MyCustom") != null && r.find("MyCustom").has(Method.COMMAND) && r.find("MyCustom").confidence() == Confidence.HIGH);

        // the real user file, written and removed again within this tick
        Path file = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("plugin_signatures.json");
        boolean existed = Files.exists(file);
        byte[] old = null;
        try {
            if (existed) old = Files.readAllBytes(file);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "// my signatures\n{ signatures: [ {id: 'filetest', name: 'FileTest', kind: 'plugin', ns: ['filetestns'],}, ], }\n", StandardCharsets.UTF_8);
            SignatureDb loaded = PluginScanner.reloadSignatures();
            check("database: prism/plugin_signatures.json is read (comments and trailing commas allowed)", loaded.byNamespace("filetestns") != null && loaded.userCount() == 1 && loaded.size() == db.size() + 1);
            Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
            SignatureDb broken = PluginScanner.reloadSignatures();
            check("database: a broken user file is reported and the bundled entries still load", !broken.problems().isEmpty() && broken.size() >= db.size());
        } catch (IOException e) {
            check("database: user file test (io) " + e.getMessage(), false);
        } finally {
            try {
                if (existed && old != null) Files.write(file, old); else Files.deleteIfExists(file);
            } catch (IOException ignored) { }
            PluginScanner.reloadSignatures();
        }
        check("database: the test left no file behind", existed || !Files.exists(file));
    }

    // ---- driver ------------------------------------------------------------------------------------------------

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
        PluginScanner sc = Prism.modules().get(PluginScanner.class);
        if (t == 30) {
            onServer(() -> mc.getSingleplayerServer().getCommands().performPrefixedCommand(mc.getSingleplayerServer().createCommandSourceStack(), "gamerule advance_time false"));
            define(sc);
        }
        if (t < 40) return;
        if (idx >= phases.size()) {
            if (++phaseTick == 20) {
                sc.hud.set(false);
                finish();
            }
            return;
        }
        Phase ph = phases.get(idx);
        if (!started) {
            started = true;
            scanned = false;
            phaseTick = 0;
            log("== " + ph.name());
            ph.setup().run();
            return;
        }
        phaseTick++;
        if (!scanned) {
            if (phaseTick < ph.delay()) return;
            scanned = true;
            phaseTick = 0;
            if (ph.go() != null) ph.go().run();
            return;
        }
        if (sc.busy() || phaseTick < 5) return;
        try {
            ph.judge().accept(sc.recompute());
        } catch (RuntimeException e) {
            check(ph.name() + ": no exception (" + e + ")", false);
            e.printStackTrace();
        }
        idx++;
        started = false;
    }
}
