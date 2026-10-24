package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.module.client.Hud;
import dev.prismglass.scan.Confidence;
import dev.prismglass.scan.Evidence;
import dev.prismglass.scan.Finding;
import dev.prismglass.scan.Method;
import dev.prismglass.scan.ScanEngine;
import dev.prismglass.scan.ScanInputs;
import dev.prismglass.scan.ScanInputs.CommandAnswer;
import dev.prismglass.scan.ScanInputs.Reply;
import dev.prismglass.scan.ScanInputs.TabAnswer;
import dev.prismglass.scan.ScanInputs.TabKind;
import dev.prismglass.scan.ScanResult;
import dev.prismglass.scan.ScanResult.Visibility;
import dev.prismglass.scan.ServerIntel;
import dev.prismglass.scan.Signature;
import dev.prismglass.scan.SignatureDb;
import dev.prismglass.scan.TreeAnalyzer;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ListSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.ChatUtil;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

/**
 * Finds out which plugins (and which anticheat) the server runs, and says when it hides them.
 *
 * <p>Many signals feed one scoring engine ({@link ScanEngine}); every plugin gets a confidence and the evidence behind
 * it, and the command surface gets a verdict (open / filtered / hidden):
 * <ul>
 *   <li>passive, always on ({@link ServerIntel}): plugin-message channel ids, ping/transaction packets (anticheats),
 *       resource pack hosts, how often the command tree was sent, the server brand;</li>
 *   <li>the command tree the server sent: namespaced commands, command names, Bukkit-style nodes, sub-command shapes;</li>
 *   <li>tab-completion probes (invisible to the server's log, a few per second at most): "/", "/version " and "/help "
 *       (Bukkit completes both with plugin names, which is often forgotten by plugins that hide /plugins),
 *       "/bukkit:" and "/minecraft:", a letter sweep when "/" is blocked, "/plugin:" prefixes;</li>
 *   <li>opt-in command probes (ProbeCommands, ErrorProbe): a real command the server can log. /bukkit:plugins, and
 *       harmless bare commands whose answer is compared with the answer to a made-up command (unknown vs refused).</li>
 * </ul>
 * Dropped as not working on 26.1.2: announcing plugin channels via minecraft:register (the game keeps only the id of
 * unknown custom payloads and cannot send their bytes), and timing the unknown-vs-refused answers (a ping's jitter is
 * bigger than the difference).
 */
public class PluginScanner extends Module {
    private static final String[] DEFAULT_IGNORE = {"minecraft", "bukkit", "spigot", "paper", "purpur", "pufferfish", "folia",
        "velocity", "bungeecord", "waterfall", "fabric", "forge", "neoforge"};
    private static final List<String> VERSION_FALLBACKS = List.of("/ver ", "/about ", "/bukkit:version ", "/bukkit:ver ", "/bukkit:about ");

    public final BoolSetting autoScanOnJoin = bool("AutoScanOnJoin", true, "Scan a moment after joining a server.");
    public final BoolSetting activeScan = bool("ActiveScan", true, "Ask the server to tab-complete \"/\" (finds plugins the join tree leaves out).");
    public final BoolSetting versionProbe = bool("VersionProbe", true, "Tab-complete \"/version \": Bukkit answers with the plugin names, even when /plugins is blocked.");
    public final BoolSetting helpProbe = bool("HelpProbe", true, "Tab-complete \"/help \": plugin names show up as help topics.");
    public final BoolSetting namespaceProbe = bool("NamespaceProbe", true, "Tab-complete \"/bukkit:\" and \"/minecraft:\" when the tree shows no namespaced commands, and \"/plugin:\" for plugins that were only named.");
    public final ModeSetting tabSweep = mode("TabSweep", "Auto", "Ask for \"/a\" ... \"/z\" one by one: Auto = only when \"/\" came back blocked or short.", "Off", "Auto", "Always");
    public final BoolSetting probeCommands = bool("ProbeCommands", false, "Also run /bukkit:plugins and read the answer. A real command: the server can log it.");
    public final BoolSetting errorProbe = bool("ErrorProbe", false, "Run a made-up command and a few harmless bare ones (/lp, /we, /ess...) and compare the answers. Real commands: the server can log them.");
    public final NumberSetting errorProbeCount = num("ErrorProbeCount", 5, 1, 12, 1, "How many harmless bare commands (/lp, /we, /ess ...) the error probe runs besides the made-up one and /plugins.").visibleWhen(errorProbe::get);
    public final NumberSetting probeDelay = num("ProbeDelay", 8, 4, 40, 1, "Ticks between two probes. Spigot kicks around 50 tab-completes in a burst; 8 stays far below that.");
    public final NumberSetting maxTabProbes = num("MaxTabProbes", 48, 4, 80, 1, "Most tab-completes one scan may send.");
    public final ListSetting ignoreList = add(new ListSetting("IgnoreList", "Namespaces that are the server itself, not plugins.",
        String.join(",", DEFAULT_IGNORE), ListSetting.Kind.TEXT, false, 0, List.of(DEFAULT_IGNORE)));
    public final ModeSetting minConfidence = mode("MinConfidence", "Low", "Hide plugins below this confidence in the chat list and the HUD.", "Low", "Medium", "High");
    public final BoolSetting showBrand = bool("ShowBrand", true, "Print the server software (brand, proxy) with the list.");
    public final BoolSetting showCommands = bool("ShowCommands", true, "List non-vanilla commands that no signature explains.");
    public final BoolSetting chatOutput = bool("ChatOutput", true, "Print the result in chat (hover a plugin for its evidence, click the list to copy it).");
    public final BoolSetting hud = bool("Hud", false, "Show the plugin list on the HUD (drag it in the HUD editor).");
    public final NumberSetting hudLines = num("HudLines", 12, 1, 40, 1, "Plugins shown on the HUD before \"+n more\".").visibleWhen(hud::get);

    /** Used in the tab-complete requests so the replies can be told apart from the chat box's own. */
    private static final int SCAN_ID_BASE = 0x5052_0000; // "PR" + index
    private static final int FIRST_TIMEOUT_TICKS = 40, REPLY_TIMEOUT_TICKS = 14, COMMAND_WINDOW_TICKS = 24, COMMAND_GAP_TICKS = 16;
    private static final Pattern PERMISSION = Pattern.compile("(?i)permission|not allowed|do not have|don't have|no access");
    private static final Pattern UNKNOWN_CMD = Pattern.compile("(?i)unknown (or incomplete )?command|command not found|no such command");

    private static volatile SignatureDb db;

    // netty -> main thread hand-over
    private final ConcurrentLinkedQueue<ClientboundCommandSuggestionsPacket> replies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Component> chatIn = new ConcurrentLinkedQueue<>();
    private volatile boolean treeDirty;
    private volatile boolean captureChat;

    // scan state (main thread)
    private enum StepKind { TAB, COMMAND }
    private record Step(StepKind kind, String text, TabKind tabKind, String probeCmd) {}

    private final ArrayDeque<Step> queue = new ArrayDeque<>();
    private final List<TabAnswer> tabs = new ArrayList<>();
    private final List<CommandAnswer> commandAnswers = new ArrayList<>();
    private final List<String> pluginsReply = new ArrayList<>();
    private record Pending(Step step, int sentAt) {}
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Set<String> asked = new HashSet<>();
    private boolean scanning, deep, pluginsCmdRan, sweepDecided, nsPassDone, anyReply;
    private int idCounter, tabsSent, cooldown, clock, cmdWindow;
    private Step cmdStep;
    private String baseline;
    private final List<String> cmdMessages = new ArrayList<>();
    private int joinTicks = -1, quietReport = -1;
    private ScanResult last;

    // test hooks
    public ScanInputs.StatusInfo statusOverride;
    public ServerIntel.Snapshot intelOverride;

    public PluginScanner() {
        super("PluginScanner", "Finds out which plugins the server runs, and whether it hides them.", Category.WORLD);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ServerIntel.reset());
    }

    public static SignatureDb signatures() {
        SignatureDb d = db;
        if (d == null) db = d = SignatureDb.load();
        return d;
    }

    /** Re-read the bundled and the user signature files. */
    public static SignatureDb reloadSignatures() { return db = SignatureDb.load(); }

    @Override
    public void onWorldJoin() {
        abort();
        last = null;
        if (autoScanOnJoin.get()) joinTicks = 0;
    }

    @Override public void onEnable() { if (!nullCheck()) { abort(); joinTicks = 0; } }

    private void abort() {
        queue.clear();
        pending.clear();
        replies.clear();
        chatIn.clear();
        captureChat = false;
        scanning = false;
        joinTicks = -1;
        quietReport = -1;
        cmdStep = null;
    }

    // ---- starting a scan ------------------------------------------------------------------------------

    /**
     * Start a scan now; the result is printed once the server has answered everything (a few seconds).
     *
     * @param probe also run /bukkit:plugins (a real command)
     */
    public void scan(boolean probe) { scan(probe, false); }

    /** @param deep additionally sweep the alphabet and run the error probe (real commands) regardless of the settings. */
    public void scan(boolean probe, boolean deep) {
        if (nullCheck() || mc.getConnection() == null) { ChatUtil.error("Not on a server"); return; }
        abort();
        tabs.clear();
        commandAnswers.clear();
        pluginsReply.clear();
        asked.clear();
        last = null;
        this.deep = deep;
        scanning = true;
        anyReply = false;
        sweepDecided = false;
        nsPassDone = false;
        pluginsCmdRan = false;
        tabsSent = 0;
        cooldown = 0;
        baseline = null;
        TreeAnalyzer.Report tree = readTree();
        boolean noNs = tree.namespaced().isEmpty();

        if (activeScan.get()) tab("/", TabKind.ROOT);
        if (versionProbe.get()) tab("/version ", TabKind.VERSION);
        if (helpProbe.get()) tab("/help ", TabKind.HELP);
        if (namespaceProbe.get() && noNs) {
            tab("/bukkit:", TabKind.PREFIX);
            tab("/minecraft:", TabKind.PREFIX);
        }
        if (tabSweep.is("Always") || deep) {
            // the whole alphabet, one letter at a time ("/a" ... "/z"): lists every root command even if "/" alone is blocked
            sweepDecided = true;
            for (char c = 'a'; c <= 'z'; c++) tab("/" + c, TabKind.ROOT);
        }
        if (probe || probeCommands.get()) queue.add(new Step(StepKind.COMMAND, "bukkit:plugins", null, "bukkit:plugins"));
        if (errorProbe.get() || deep) {
            String unknown = "prsm" + Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);
            queue.add(new Step(StepKind.COMMAND, unknown, null, null)); // the baseline every other answer is compared with
            queue.add(new Step(StepKind.COMMAND, "plugins", null, "plugins"));
            List<Signature> probes = new ArrayList<>();
            for (Signature s : signatures().all()) if (s.probe() != null) probes.add(s);
            probes.sort(Comparator.comparingInt(Signature::priority).reversed());
            for (int i = 0; i < Math.min(errorProbeCount.getInt(), probes.size()); i++) queue.add(new Step(StepKind.COMMAND, probes.get(i).probe(), null, probes.get(i).probe()));
        }
        if (queue.isEmpty()) finishScan();
    }

    private void tab(String text, TabKind kind) {
        if (asked.add(text)) queue.add(new Step(StepKind.TAB, text, kind, null));
    }

    /** Goes before everything already queued (the answer it is a fallback for matters more than a sweep letter). */
    private void tabFirst(String text, TabKind kind) {
        if (asked.add(text)) queue.addFirst(new Step(StepKind.TAB, text, kind, null));
    }

    private TreeAnalyzer.Report readTree() {
        if (mc.getConnection() == null) return TreeAnalyzer.Report.empty();
        return TreeAnalyzer.analyze(mc.getConnection().getCommands().getRoot());
    }

    // ---- ticking ---------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (joinTicks >= 0 && ++joinTicks == 60) { joinTicks = -1; scan(false); }
        if (treeDirty) {
            treeDirty = false;
            if (!scanning && joinTicks < 0 && last != null && autoScanOnJoin.get()) quietReport = 5; // a resent tree: refresh quietly
        }
        if (quietReport >= 0 && --quietReport < 0) { last = null; recompute(); }
        if (!scanning) return;

        ClientboundCommandSuggestionsPacket r;
        while ((r = replies.poll()) != null) answer(r);

        String text;
        while ((text = pollChat()) != null) cmdMessages.add(text);

        clock++;
        if (cooldown > 0) cooldown--;
        // Paper does not answer a tab-complete whose result is empty, so silence is an (empty) answer, after a short wait
        int timeout = anyReply ? REPLY_TIMEOUT_TICKS : FIRST_TIMEOUT_TICKS;
        for (Iterator<Map.Entry<Integer, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Pending p = it.next().getValue();
            if (clock - p.sentAt() <= timeout) continue;
            it.remove();
            tabs.add(new TabAnswer(p.step().text(), p.step().tabKind(), false, 0, 0, List.of()));
            followUp(p.step(), List.of());
        }
        if (cmdStep != null) {
            if (--cmdWindow <= 0) closeCommand();
            return;
        }
        Step next = queue.peek();
        if (next == null) {
            if (pending.isEmpty() && !afterPass()) finishScan();
            return;
        }
        if (cooldown > 0 || (next.kind() == StepKind.COMMAND && !pending.isEmpty())) return;
        queue.poll();
        send(next);
    }

    private String pollChat() {
        Component c = chatIn.poll();
        return c == null ? null : c.getString();
    }

    private void send(Step step) {
        if (mc.getConnection() == null) { abort(); return; }
        if (step.kind() == StepKind.TAB) {
            if (tabsSent >= maxTabProbes.getInt()) return; // budget spent: drop it, the next step may go right away
            int id = SCAN_ID_BASE | (idCounter++ & 0xFFFF);
            pending.put(id, new Pending(step, clock));
            tabsSent++;
            mc.getConnection().send(new ServerboundCommandSuggestionPacket(id, step.text()));
            cooldown = probeDelay.getInt();
        } else {
            cmdStep = step;
            cmdMessages.clear();
            chatIn.clear();
            captureChat = true;
            cmdWindow = COMMAND_WINDOW_TICKS;
            mc.getConnection().sendCommand(step.text());
        }
    }

    private void answer(ClientboundCommandSuggestionsPacket p) {
        Pending pend = pending.remove(p.id());
        if (pend == null) return;
        anyReply = true;
        Step step = pend.step();
        List<String> entries = new ArrayList<>();
        for (ClientboundCommandSuggestionsPacket.Entry e : p.suggestions()) entries.add(e.text());
        tabs.add(new TabAnswer(step.text(), step.tabKind(), true, p.start(), p.length(), List.copyOf(entries)));
        followUp(step, entries);
    }

    /** Decide the next probes from an answer. */
    private void followUp(Step step, List<String> entries) {
        if (step.tabKind() == TabKind.VERSION && entries.isEmpty()) {
            // /version blocked or renamed: try its aliases next (in this order, ahead of any sweep)
            for (int i = VERSION_FALLBACKS.size() - 1; i >= 0; i--) tabFirst(VERSION_FALLBACKS.get(i), TabKind.VERSION);
        }
        if (step.tabKind() == TabKind.ROOT && step.text().equals("/") && !sweepDecided) {
            sweepDecided = true;
            TreeAnalyzer.Report tree = readTree();
            boolean blocked = entries.isEmpty() && !tree.isEmpty() || (tree.plain().size() >= 8 && entries.size() * 2 < tree.plain().size());
            if (tabSweep.is("Auto") && blocked) for (char c = 'a'; c <= 'z'; c++) tab("/" + c, TabKind.ROOT);
        }
    }

    /**
     * After the queue drained: a plugin known only by its name (from /version or /help) has an unknown command list. Bukkit
     * registers its commands as "pluginname:command", so a tab-complete of "/pluginname:" lists them (and confirms the
     * namespace) even when "/" is blocked and the alphabet sweep is off. Returns true if it queued more.
     */
    private boolean afterPass() {
        if (nsPassDone || !namespaceProbe.get()) return false;
        nsPassDone = true;
        int n = 0;
        for (Finding f : engine().plugins) {
            if (n >= 8) break;
            boolean named = f.has(Method.VERSION_TAB) || f.has(Method.PLUGINS_CMD) || f.has(Method.HELP_TAB);
            boolean ns = f.has(Method.TREE_NS) || f.has(Method.TAB_NS) || f.has(Method.PREFIX_TAB);
            if (!named || ns || f.builtin()) continue;
            String key = f.sig != null && !f.sig.ns().isEmpty() ? f.sig.ns().get(0) : f.name().toLowerCase(Locale.ROOT);
            if (ignoredNs(key)) continue;
            tab("/" + key + ":", TabKind.PREFIX);
            n++;
        }
        return !queue.isEmpty();
    }

    private void closeCommand() {
        Step step = cmdStep;
        cmdStep = null;
        captureChat = false;
        cooldown = COMMAND_GAP_TICKS;
        String all = String.join("\n", cmdMessages);
        cmdMessages.clear();
        if (step.probeCmd() == null) { // the made-up command: remember what "unknown" looks like on this server
            baseline = normalize(all, step.text());
            return;
        }
        if (step.text().equals("bukkit:plugins")) {
            pluginsCmdRan = true;
            pluginsReply.addAll(parsePluginList(all));
            return;
        }
        Reply reply = classify(all, step);
        commandAnswers.add(new CommandAnswer(step.probeCmd(), reply));
        if (step.probeCmd().equals("plugins") && reply == Reply.EXISTS) pluginsReply.addAll(parsePluginList(all));
    }

    private Reply classify(String text, Step step) {
        if (text.isBlank()) return Reply.SILENT;
        String norm = normalize(text, step.text());
        // a server may reword "unknown command" (the made-up command shows how), but an unknown-command text is unknown by definition
        if ((baseline != null && norm.equals(baseline)) || UNKNOWN_CMD.matcher(text).find()) return Reply.UNKNOWN;
        if (PERMISSION.matcher(text).find()) return Reply.DENIED;
        String lower = text.toLowerCase(Locale.ROOT);
        Signature sig = null;
        for (Signature s : signatures().all()) if (step.probeCmd().equals(s.probe())) { sig = s; break; }
        // other chat can arrive in the window: only count an answer that talks about this command or plugin
        if (step.probeCmd().equals("plugins") && lower.contains("plugin")) return Reply.EXISTS;
        if (lower.contains("usage") || lower.contains("/" + step.text()) || (sig != null && lower.contains(sig.name().toLowerCase(Locale.ROOT)))) return Reply.EXISTS;
        return Reply.SILENT;
    }

    /** Lowercase, no colour codes, the command's own name replaced, whitespace squeezed. */
    private static String normalize(String text, String command) {
        String plain = ChatFormatting.stripFormatting(text);
        String s = (plain == null ? text : plain).toLowerCase(Locale.ROOT).replace(command.toLowerCase(Locale.ROOT), "<cmd>");
        return s.replaceAll("\\s+", " ").strip();
    }

    /**
     * Bukkit: "Plugins (3): A, B, C". Paper: "Server Plugins (3):" then "Bukkit Plugins:" / "Paper Plugins:" and " - A, B, C"
     * lines, in one or several messages. Anything comma-separated after a header counts.
     */
    public static List<String> parsePluginList(String text) {
        List<String> out = new ArrayList<>();
        boolean inList = false;
        String plain = ChatFormatting.stripFormatting(text); // Bukkit colours the list with legacy codes
        Pattern name = Pattern.compile("[A-Za-z0-9_.\\-]+");
        for (String raw : (plain == null ? text : plain).split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("plugins (") || lower.endsWith("plugins:")) {
                inList = true;
                int c = line.indexOf(':');
                line = c < 0 ? "" : line.substring(c + 1).strip();
                if (line.isEmpty()) continue;
            } else if (UNKNOWN_CMD.matcher(lower).find() || PERMISSION.matcher(lower).find()) {
                continue;
            }
            if (!inList) continue;
            if (line.startsWith("-")) line = line.substring(1).strip();
            for (String part : line.split(",")) {
                var m = name.matcher(part.strip());
                if (m.find() && !m.group().equalsIgnoreCase("none")) out.add(m.group());
            }
        }
        return out;
    }

    private void finishScan() {
        scanning = false;
        captureChat = false;
        last = null;
        recompute();
        report();
    }

    // ---- results ---------------------------------------------------------------------------------------

    private ScanInputs inputs() {
        ServerIntel.Snapshot intel = intelOverride != null ? intelOverride : ServerIntel.snapshot();
        String brand = mc.getConnection() == null ? null : mc.getConnection().serverBrand();
        ScanInputs.StatusInfo status = statusOverride != null ? statusOverride : status();
        return new ScanInputs(readTree(), List.copyOf(tabs), intel, brand, status, List.copyOf(commandAnswers), List.copyOf(pluginsReply), pluginsCmdRan,
            ns -> ignoredNs(ns));
    }

    private ScanInputs.StatusInfo status() {
        ServerData sd = mc.getCurrentServer();
        String name = sd == null || sd.version == null ? null : sd.version.getString();
        var v = SharedConstants.getCurrentVersion();
        return new ScanInputs.StatusInfo(name, sd == null ? 0 : sd.protocol, v.name(), v.protocolVersion(), FabricLoader.getInstance().isModLoaded("viafabricplus"));
    }

    private boolean ignoredNs(String ns) {
        for (String i : ignoreList.items()) if (i.equalsIgnoreCase(ns)) return true;
        return false;
    }

    private ScanResult engine() { return ScanEngine.analyze(inputs(), signatures()); }

    /** Recompute from what is known now (new passive observations included). */
    public ScanResult recompute() {
        last = engine();
        return last;
    }

    /** The latest result (computed on demand). */
    public ScanResult result() { return last != null ? last : recompute(); }

    public boolean busy() { return scanning; }

    /** Display names of the plugins found (respects MinConfidence). */
    public List<String> plugins() {
        List<String> out = new ArrayList<>();
        for (Finding f : shown(result())) out.add(f.name());
        return out;
    }

    public String brand() { return mc.getConnection() == null ? null : mc.getConnection().serverBrand(); }

    /** Tab answers collected by the last scan (tests). */
    public List<TabAnswer> tabAnswers() { return Collections.unmodifiableList(tabs); }

    public List<CommandAnswer> commandAnswers() { return Collections.unmodifiableList(commandAnswers); }

    private List<Finding> shown(ScanResult r) {
        int min = minConfidence.is("High") ? 2 : minConfidence.is("Medium") ? 1 : 0;
        List<Finding> out = new ArrayList<>();
        for (Finding f : r.plugins) if (f.confidence().ordinal() >= min) out.add(f);
        return out;
    }

    // ---- report ----------------------------------------------------------------------------------------

    private static String verdictColor(Visibility v) {
        return switch (v) {
            case OPEN -> "§a";
            case FILTERED -> "§e";
            case HIDDEN -> "§c";
            default -> "§7";
        };
    }

    private void report() {
        if (!chatOutput.get()) return;
        ScanResult r = result();
        boolean plain = dev.prismglass.streamproof.Overlay.active(); // chat is captured while StreamProof is on: plain toasts only
        if (showBrand.get()) {
            String brand = brand();
            StringBuilder sw = new StringBuilder("Server: §f").append(brand == null ? "unknown" : brand);
            for (Finding f : r.software) sw.append(" §8· §7").append(f.name()).append(f.confidence() == Confidence.LOW ? "?" : "");
            ChatUtil.info(sw.toString());
        }
        ScanResult.View v = r.view;
        ChatUtil.info("Commands: " + verdictColor(v.verdict()) + v.verdict().label.toUpperCase(Locale.ROOT)
            + (v.verdict() == Visibility.FILTERED || v.verdict() == Visibility.HIDDEN ? " §7- what is listed may be incomplete" : ""));
        if (v.verdict() == Visibility.FILTERED || v.verdict() == Visibility.HIDDEN)
            for (String reason : v.reasons()) ChatUtil.info("§8  - " + reason);

        List<Finding> list = shown(r);
        if (list.isEmpty()) {
            ChatUtil.info(switch (v.verdict()) {
                case HIDDEN, FILTERED -> "No plugin found, but the commands look hidden: that does §fnot§7 mean there are none.";
                case NONE -> "No plugins: this looks like a vanilla or modded server without a plugin layer.";
                case UNKNOWN -> "Not enough data yet; try .plugins again in a moment.";
                default -> "No plugins detected and the commands are exposed normally: the server probably runs none.";
            });
        } else {
            MutableComponent line = Component.literal("Plugins (" + list.size() + "): ").withStyle(ChatFormatting.GRAY);
            List<String> copy = new ArrayList<>();
            boolean first = true;
            for (Finding f : list) {
                if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                first = false;
                copy.add(f.name());
                line.append(Component.literal(f.name()).withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover(f)))
                    .withColor(color(f.confidence()))));
                if (f.confidence() == Confidence.LOW) line.append(Component.literal("?").withStyle(ChatFormatting.DARK_GRAY));
            }
            String joined = String.join(", ", copy);
            line.withStyle(s -> s.withClickEvent(new ClickEvent.CopyToClipboard(joined)));
            if (plain) ChatUtil.info("Plugins (" + list.size() + "): §f" + joined);
            else ChatUtil.send(Component.literal("").append(Component.literal("◆ ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("Prism ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY)).append(line));
            if (!r.anticheats.isEmpty()) {
                StringBuilder ac = new StringBuilder("Anticheat: ");
                for (Finding f : r.anticheats) ac.append(f.confidence().color).append(f.name()).append(" §8(").append(f.confidence().label).append(") ");
                ChatUtil.info(ac.toString().strip());
            }
        }
        if (showCommands.get() && !r.customCommands.isEmpty()) {
            List<String> c = r.customCommands;
            ChatUtil.info("Custom commands (" + c.size() + "): §f" + String.join(", ", c.subList(0, Math.min(c.size(), 14))) + (c.size() > 14 ? " §8+" + (c.size() - 14) + " more" : ""));
        }
        if (!r.otherChannels.isEmpty()) ChatUtil.info("Other channels: §f" + String.join(", ", r.otherChannels.subList(0, Math.min(r.otherChannels.size(), 6))));
        ChatUtil.info("§8Hover a name for its evidence, click the list to copy it. §7.plugins why <name>§8 shows everything.");
    }

    private static ChatFormatting color(Confidence c) {
        return switch (c) {
            case CONFIRMED -> ChatFormatting.GREEN;
            case HIGH -> ChatFormatting.WHITE;
            case MEDIUM -> ChatFormatting.YELLOW;
            case LOW -> ChatFormatting.DARK_GRAY;
        };
    }

    private static Component hover(Finding f) {
        MutableComponent c = Component.literal(f.name()).withStyle(ChatFormatting.WHITE)
            .append(Component.literal("  " + f.confidence().label + " (" + f.score() + "%)").withStyle(color(f.confidence())));
        if (f.sig != null) c.append(Component.literal("\n" + f.kind()).withStyle(ChatFormatting.DARK_AQUA));
        for (Evidence e : f.evidence())
            c.append(Component.literal("\n" + (e.method().active ? "▸ " : "• ") + e.method().label + ": " + e.detail()).withStyle(ChatFormatting.GRAY));
        if (!f.commands().isEmpty()) {
            List<String> cmds = new ArrayList<>(f.commands());
            c.append(Component.literal("\ncommands: " + String.join(", ", cmds.subList(0, Math.min(cmds.size(), 10)))).withStyle(ChatFormatting.DARK_GRAY));
        }
        return c;
    }

    /** Chat lines for {@code .plugins why [name]}. */
    public void why(String what) {
        ScanResult r = result();
        if (what == null || what.isBlank()) {
            ChatUtil.info("Commands: " + verdictColor(r.view.verdict()) + r.view.verdict().label + " §7(score " + r.view.score() + ")");
            for (String reason : r.view.reasons()) ChatUtil.info("§8  - " + reason);
            if (r.ping.total() > 0) ChatUtil.info(String.format(Locale.ROOT, "Pings: §f%d §7packets, %.0f/s, kind %s", r.ping.total(), r.ping.perSecond(), r.ping.kind()));
            ChatUtil.info("Signatures: §f" + signatures().size() + " §7(" + signatures().bundledCount() + " bundled, " + signatures().userCount() + " yours)");
            return;
        }
        Finding f = r.find(what);
        if (f == null) { ChatUtil.error("No finding called " + what); return; }
        ChatUtil.info(f.confidence().color + f.name() + " §7- " + f.confidence().label + " (" + f.score() + "%)" + (f.sig == null ? "" : " §8" + f.kind()));
        for (Evidence e : f.evidence())
            ChatUtil.info("§8  " + (e.method().active ? "asked: " : "seen: ") + "§7" + e.method().label + " §8(" + e.weight() + ") §7" + e.detail());
        if (f.sig != null && !f.sig.note().isEmpty()) ChatUtil.info("§8  note: " + f.sig.note());
    }

    // ---- packets (netty thread: only hand data over) ----------------------------------------------------

    @Override
    public void onPacketReceive(PacketEvent e) {
        if (e.packet instanceof ClientboundCommandsPacket) {
            treeDirty = true;
        } else if (e.packet instanceof ClientboundCommandSuggestionsPacket p && (p.id() & 0xFFFF0000) == SCAN_ID_BASE) {
            replies.add(p);
            e.cancel(); // ours, not the chat box's
        } else if (captureChat && e.packet instanceof ClientboundSystemChatPacket p && !p.overlay()) {
            chatIn.add(p.content());
        }
    }

    // ---- HUD -------------------------------------------------------------------------------------------

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (!hud.get() || (mc.gui.hud.isHidden() && !Hud.editing)) return;
        ScanResult r = last;
        List<Finding> list = r == null ? List.of() : shown(r);
        List<String> lines = new ArrayList<>();
        String verdict = r == null ? "" : " " + verdictColor(r.view.verdict()) + r.view.verdict().label;
        lines.add("Plugins §f" + list.size() + verdict);
        int max = hudLines.getInt(), i = 0;
        for (Finding f : list) {
            if (i++ >= max) { lines.add("§8+" + (list.size() - max) + " more"); break; }
            lines.add(f.confidence().color + f.name() + (f.confidence() == Confidence.LOW ? "§8?" : ""));
        }
        float w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        w += 12;
        float h = lines.size() * 10 + 6;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        Hud hudModule = Prism.modules().get(Hud.class);
        float[] p = hudModule != null && hudModule.isEnabled() ? hudModule.place("Plugins", w, h, 4, 26, sw, sh) : new float[]{4, 26};
        Glass.panel(ctx, p[0], p[1], w, h, Prism.modules().get(ClickGui.class).panelStyle());
        for (int k = 0; k < lines.size(); k++) ctx.text(mc.font, "§7" + lines.get(k), (int) p[0] + 6, (int) p[1] + 4 + k * 10, 0xFFFFFFFF, true);
    }

    @Override public String getInfo() {
        if (last == null) return null;
        int n = shown(last).size();
        return last.view.verdict() == Visibility.HIDDEN || last.view.verdict() == Visibility.FILTERED ? n + "?" : n == 0 ? null : String.valueOf(n);
    }
}
