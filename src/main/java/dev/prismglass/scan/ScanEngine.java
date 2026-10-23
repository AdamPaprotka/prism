package dev.prismglass.scan;

import dev.prismglass.scan.ScanInputs.CommandAnswer;
import dev.prismglass.scan.ScanInputs.Reply;
import dev.prismglass.scan.ScanInputs.TabAnswer;
import dev.prismglass.scan.ScanInputs.TabKind;
import dev.prismglass.scan.ScanResult.PingProfile;
import dev.prismglass.scan.ScanResult.View;
import dev.prismglass.scan.ScanResult.Visibility;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combines every signal into one result. Pure: it only reads {@link ScanInputs} and the {@link SignatureDb}, so the
 * test harness can feed it real captures and invented ones alike.
 *
 * <p>Each method gives a plugin evidence with a weight (how sure that fact alone makes us); the plugin's confidence is
 * the noisy-or of the strongest evidence per family (see {@link Method}). The same inputs also decide whether the
 * server hides its commands ({@link View}).
 */
public final class ScanEngine {
    private static final Set<String> SOFTWARE_NS = Set.of("minecraft", "bukkit", "spigot", "paper", "purpur", "pufferfish", "folia",
        "velocity", "bungeecord", "waterfall", "fabric", "forge", "neoforge");
    /** /help topics that are not plugins. */
    private static final Set<String> HELP_GENERAL = Set.of("aliases", "bukkit", "minecraft", "spigot", "paper", "general", "commands");
    private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final Pattern VERSION_TOKEN = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    private ScanEngine() {}

    public static ScanResult analyze(ScanInputs in, SignatureDb db) {
        return new Run(in, db).run();
    }

    private static final class Run {
        final ScanInputs in;
        final SignatureDb db;
        final TreeAnalyzer.Report tree;
        final Map<String, Finding> found = new LinkedHashMap<>();
        final Set<String> tabPlain = new TreeSet<>();
        final Set<String> tabNamespaced = new TreeSet<>();
        final Set<String> matchedCommands = new HashSet<>();
        final List<String> otherChannels = new ArrayList<>();
        PingProfile ping = PingProfile.none();

        Run(ScanInputs in, SignatureDb db) {
            this.in = in;
            this.db = db;
            this.tree = in.tree() == null ? TreeAnalyzer.Report.empty() : in.tree();
        }

        // ---- finding bookkeeping ----------------------------------------------------------------------

        Finding finding(Signature sig, String fallbackName) {
            String key = sig != null ? sig.id() : "x:" + fallbackName.toLowerCase(Locale.ROOT);
            return found.computeIfAbsent(key, k -> new Finding(k, sig, sig != null ? sig.name() : fallbackName));
        }

        Signature lookup(String name) {
            Signature s = db.byName(name);
            return s != null ? s : db.byNamespace(name);
        }

        boolean ignoredNs(String ns) {
            return SOFTWARE_NS.contains(ns) || (in.ignored() != null && in.ignored().test(ns));
        }

        // ---- the methods -------------------------------------------------------------------------------

        ScanResult run() {
            treeNamespaces();
            tabAnswers();
            commandNames();
            shapes();
            channels();
            pings();
            software();
            packs();
            commandProbes();
            translation();
            return finish();
        }

        void treeNamespaces() {
            for (Map.Entry<String, Set<String>> e : tree.namespaced().entrySet()) {
                String ns = e.getKey();
                if (ignoredNs(ns)) continue;
                Finding f = finding(db.byNamespace(ns), ns);
                f.commands().addAll(e.getValue());
                f.add(Method.TREE_NS, "\"" + ns + ":" + e.getValue().iterator().next() + "\" is in the command tree", 90);
            }
        }

        void tabAnswers() {
            for (TabAnswer t : in.tabs()) {
                if (!t.answered()) continue;
                switch (t.kind()) {
                    case ROOT -> {
                        for (String entry : t.entries()) {
                            int c = entry.indexOf(':');
                            if (c <= 0 || c == entry.length() - 1) {
                                tabPlain.add(entry.toLowerCase(Locale.ROOT));
                                continue;
                            }
                            String ns = entry.substring(0, c).toLowerCase(Locale.ROOT);
                            tabNamespaced.add(entry.toLowerCase(Locale.ROOT));
                            if (ignoredNs(ns) || !PLUGIN_NAME.matcher(ns).matches()) continue;
                            Finding f = finding(db.byNamespace(ns), ns);
                            f.commands().add(entry.substring(c + 1));
                            f.add(Method.TAB_NS, "\"" + entry + "\" came back from tab-complete (" + t.sent().strip() + ")", 90);
                        }
                    }
                    case PREFIX -> {
                        for (String entry : t.entries()) {
                            int c = entry.indexOf(':');
                            if (c <= 0 || c == entry.length() - 1) continue;
                            String ns = entry.substring(0, c).toLowerCase(Locale.ROOT);
                            tabNamespaced.add(entry.toLowerCase(Locale.ROOT));
                            if (ignoredNs(ns) || !PLUGIN_NAME.matcher(ns).matches()) continue;
                            Finding f = finding(db.byNamespace(ns), ns);
                            f.commands().add(entry.substring(c + 1));
                            f.add(Method.PREFIX_TAB, "tab-complete of \"" + t.sent().strip() + "\" lists \"" + entry + "\"", 90);
                        }
                    }
                    case VERSION -> {
                        for (String entry : t.entries()) {
                            String name = entry.strip();
                            if (name.isEmpty() || name.startsWith("/") || !PLUGIN_NAME.matcher(name).matches()) continue;
                            if (ignoredNs(name.toLowerCase(Locale.ROOT))) continue;
                            Finding f = finding(lookup(name), name);
                            f.rename(name);
                            f.add(Method.VERSION_TAB, "the server completes \"" + t.sent().strip() + "\" with \"" + name + "\"", 99);
                        }
                    }
                    case HELP -> {
                        for (String entry : t.entries()) {
                            String name = entry.strip();
                            if (name.isEmpty() || name.startsWith("/") || !PLUGIN_NAME.matcher(name).matches()) continue;
                            String lower = name.toLowerCase(Locale.ROOT);
                            if (HELP_GENERAL.contains(lower) || ignoredNs(lower) || TreeAnalyzer.isStandard(lower)) continue;
                            Signature sig = lookup(name);
                            // /help also lists every command by its bare name (and aliases): lowercase unknown topics are commands, not plugins
                            if (sig == null && (Character.isLowerCase(name.charAt(0)) || tree.plain().contains(lower) || tabPlain.contains(lower))) continue;
                            Finding f = finding(sig, name);
                            if (sig != null) f.rename(name);
                            f.add(Method.HELP_TAB, "\"" + name + "\" is a help topic", sig != null ? 70 : 50);
                        }
                    }
                }
            }
            for (String name : in.pluginsReply()) {
                if (ignoredNs(name.toLowerCase(Locale.ROOT))) continue;
                Finding f = finding(lookup(name), name);
                f.rename(name);
                f.add(Method.PLUGINS_CMD, "listed by the server's own plugin-list command", 99);
            }
        }

        void commandNames() {
            Set<String> plain = new HashSet<>(tree.plain());
            plain.addAll(tabPlain);
            plain.removeIf(TreeAnalyzer::isStandard);
            if (plain.isEmpty()) return;
            for (Signature sig : db.all()) {
                if (sig.software()) continue;
                List<String> strong = new ArrayList<>(), weak = new ArrayList<>();
                for (String c : sig.cmds()) if (plain.contains(c)) strong.add(c);
                for (String c : sig.weak()) if (plain.contains(c)) weak.add(c);
                if (strong.isEmpty() && weak.size() < 2 && !found.containsKey(sig.id())) continue;
                if (strong.isEmpty() && weak.isEmpty()) continue;
                int weight = strong.isEmpty() ? Math.min(40, 12 * weak.size()) : Math.min(88, 55 + 14 * (strong.size() - 1) + 5 * weak.size());
                matchedCommands.addAll(strong);
                matchedCommands.addAll(weak);
                Finding f = finding(sig, sig.name());
                List<String> all = new ArrayList<>(strong);
                all.addAll(weak);
                f.commands().addAll(all);
                f.add(Method.COMMAND, (strong.isEmpty() ? "commands also used by it: " : "its commands: ") + String.join(", ", all.subList(0, Math.min(all.size(), 6))), weight);
            }
        }

        void shapes() {
            if (tree.isEmpty()) return;
            for (Signature sig : db.all()) {
                for (Signature.Shape shape : sig.shapes()) {
                    for (TreeAnalyzer.Root root : tree.roots()) {
                        if (root.literals().isEmpty()) continue;
                        List<String> hit = new ArrayList<>();
                        for (String h : shape.has()) if (root.literals().contains(h)) hit.add(h);
                        if (hit.size() < shape.min()) continue;
                        boolean usual = sig.cmds().contains(root.name().toLowerCase(Locale.ROOT)) || root.name().toLowerCase(Locale.ROOT).endsWith(":" + sig.id());
                        finding(sig, sig.name()).add(Method.SHAPE, "/" + root.name() + " has the sub-commands " + String.join(", ", hit.subList(0, Math.min(hit.size(), 5)))
                            + (usual ? "" : " (not its usual name)"), 62);
                    }
                }
            }
        }

        void channels() {
            for (ServerIntel.Channel ch : in.intel().channels()) {
                String id = ch.id();
                if (id.equals("minecraft:brand") || id.equals("minecraft:register") || id.equals("minecraft:unregister")) continue;
                boolean matched = false;
                for (Signature sig : db.all()) {
                    int strength = sig.matchChannel(id);
                    if (strength == 0) continue;
                    matched = true;
                    finding(sig, sig.name()).add(Method.CHANNEL, "the server sent on channel " + id + " (" + ch.phase() + ", " + ch.count() + "x)", strength == 2 ? 85 : 70);
                }
                if (!matched) otherChannels.add(id);
            }
        }

        void pings() {
            ServerIntel.Snapshot s = in.intel();
            ping = profilePings(s);
            switch (ping.kind()) {
                case "grim" -> {
                    Signature grim = null;
                    for (Signature sig : db.all()) if ("grim".equals(sig.ping())) grim = sig;
                    finding(grim, "GrimAC").add(Method.PING, pingDetail("ids descend by one in the negative short range, Grim's transaction scheme"), 70);
                }
                case "negative" -> finding(null, "Transaction anticheat").add(Method.PING, pingDetail("ping ids in the negative short range"), 55);
                case "stream" -> finding(null, "Transaction anticheat").add(Method.PING, pingDetail("a steady stream of ping packets (vanilla sends none)"), 45);
                default -> { }
            }
            if (!ping.kind().equals("none")) {
                // corroborate anticheats that were found by other means and use pings
                for (Finding f : List.copyOf(found.values())) {
                    if (f.sig != null && f.sig.ping() != null && !f.has(Method.PING) && !(f.sig.ping().equals("grim") && !ping.kind().equals("grim")))
                        f.add(Method.PING, pingDetail("transaction pings, as " + f.sig.name() + " sends"), 45);
                }
            }
        }

        String pingDetail(String what) {
            return ping.total() + " ping packets, " + String.format(Locale.ROOT, "%.0f", ping.perSecond()) + "/s: " + what;
        }

        void software() {
            String brand = in.brand() != null ? in.brand() : in.intel().brand();
            String status = in.status() == null ? null : in.status().versionName();
            for (Signature sig : db.all()) {
                if (brand != null) for (Pattern p : sig.brand()) if (p.matcher(brand).find()) { finding(sig, sig.name()).add(Method.BRAND, "server brand \"" + brand + "\"", 85); break; }
                if (status != null) for (Pattern p : sig.status()) if (p.matcher(status).find()) { finding(sig, sig.name()).add(Method.STATUS, "server list says \"" + status + "\"", 70); break; }
            }
        }

        void packs() {
            for (ServerIntel.Pack pack : in.intel().packs()) {
                for (Signature sig : db.all())
                    for (Pattern p : sig.pack())
                        if (p.matcher(pack.host()).find()) { finding(sig, sig.name()).add(Method.PACK, "resource pack comes from " + pack.host(), 40); break; }
            }
        }

        void commandProbes() {
            for (CommandAnswer a : in.commandProbes()) {
                for (Signature sig : db.all()) {
                    if (a.command().equals(sig.probe())) {
                        switch (a.reply()) {
                            case EXISTS -> finding(sig, sig.name()).add(Method.ERROR_PROBE, "/" + a.command() + " is answered with something other than \"unknown command\"", 85);
                            case DENIED -> finding(sig, sig.name()).add(Method.ERROR_PROBE, "/" + a.command() + " is refused for permission, which unknown commands never are", 85);
                            case SILENT -> finding(sig, sig.name()).add(Method.ERROR_PROBE, "/" + a.command() + " was swallowed without an unknown-command answer", 25);
                            default -> { }
                        }
                    }
                }
            }
        }

        void translation() {
            ScanInputs.StatusInfo st = in.status();
            if (st == null || st.versionName() == null || st.translatorLoaded() || st.clientVersion().isEmpty()) return;
            Matcher m = VERSION_TOKEN.matcher(st.versionName());
            boolean any = false, same = false;
            while (m.find()) {
                any = true;
                if (m.group().equals(st.clientVersion())) same = true;
            }
            if (!any || same) return;
            Finding target = null;
            for (Finding f : found.values()) if (f.sig != null && f.sig.kind().equals("protocol") && f.score() >= 50) { target = f; break; }
            if (target == null) target = finding(null, "Version translation");
            target.add(Method.STATUS, "the server list says \"" + st.versionName() + "\" but you joined with " + st.clientVersion(), 60);
        }

        // ---- result -------------------------------------------------------------------------------------

        ScanResult finish() {
            List<Finding> plugins = new ArrayList<>(), builtins = new ArrayList<>(), software = new ArrayList<>(), anticheats = new ArrayList<>();
            for (Finding f : found.values()) {
                if (f.score() < 1) continue;
                if (f.sig != null && f.sig.software()) {
                    if (f.score() >= 50) software.add(f);
                } else if (f.builtin()) builtins.add(f);
                else plugins.add(f);
            }
            Comparator<Finding> order = Comparator.comparingInt(Finding::score).reversed().thenComparing(f -> f.name().toLowerCase(Locale.ROOT));
            plugins.sort(order);
            software.sort(order);
            builtins.sort(order);
            for (Finding f : plugins) if (f.kind().equals("anticheat") || f.key.startsWith("x:transaction")) anticheats.add(f);

            Set<String> custom = new TreeSet<>(tree.plain());
            custom.addAll(tabPlain);
            custom.removeIf(TreeAnalyzer::isStandard);
            custom.removeAll(matchedCommands);

            View view = view(plugins, software);
            return new ScanResult(List.copyOf(plugins), List.copyOf(builtins), List.copyOf(software), List.copyOf(anticheats), view, ping,
                List.copyOf(custom), List.copyOf(otherChannels), tree);
        }

        View view(List<Finding> plugins, List<Finding> software) {
            List<String> reasons = new ArrayList<>();
            int pts = 0;
            boolean bukkitLike = tree.bukkitStyleCount() >= 2;
            for (Finding f : software) if (f.sig != null && f.sig.bukkit()) bukkitLike = true;
            if (tree.namespaced().containsKey("bukkit") || tree.namespaced().containsKey("spigot")) bukkitLike = true;
            boolean haveData = !tree.isEmpty() || in.tabs().stream().anyMatch(TabAnswer::answered);
            if (!haveData) return new View(Visibility.UNKNOWN, 0, List.of("no command tree or tab-complete answer yet"), bukkitLike);

            String brand = in.brand() != null ? in.brand() : in.intel().brand();
            boolean vanillaBrand = brand != null && brand.equalsIgnoreCase("vanilla");
            if (vanillaBrand && tree.bukkitStyleCount() >= 2) reasons.add("the brand says \"vanilla\" but the tree has Bukkit-style commands: the brand is spoofed or rewritten");

            if (bukkitLike && !tree.isEmpty()) {
                if (tree.namespaced().isEmpty()) {
                    pts += 2;
                    reasons.add("the tree has no namespaced commands, but Paper/Spigot normally send minecraft: and bukkit: copies");
                }
                boolean anyInfo = false;
                Set<String> all = tree.all();
                for (String c : TreeAnalyzer.BUKKIT_INFO) if (all.contains(c) || all.contains("bukkit:" + c)) anyInfo = true;
                if (!anyInfo) {
                    pts += 3;
                    reasons.add("/plugins, /version and /about are missing although every player sees them by default");
                }
                if (tree.roots().size() <= 8) {
                    pts += 1;
                    reasons.add("only " + tree.roots().size() + " commands are visible");
                }
            }

            TabAnswer root = null;
            for (TabAnswer t : in.tabs()) if (t.kind() == TabKind.ROOT && t.sent().equals("/")) { root = t; break; }
            if (root != null && bukkitLike) {
                // Paper stays silent for an empty result, so "no answer" and "empty answer" are the same thing here
                if (root.entries().isEmpty() && !tree.isEmpty()) {
                    pts += 3;
                    reasons.add("tab-complete of \"/\" came back empty although the tree lists " + tree.roots().size() + " commands");
                } else if (tree.plain().size() >= 8 && root.entries().size() * 2 < tree.plain().size()) {
                    pts += 2;
                    reasons.add("tab-complete of \"/\" is much shorter (" + root.entries().size() + ") than the tree (" + tree.plain().size() + ")");
                }
            }
            if (!tabNamespaced.isEmpty()) {
                Set<String> inTree = tree.all();
                int extra = 0;
                for (String n : tabNamespaced) if (!inTree.contains(n)) extra++;
                if (extra > 0) {
                    pts += 2;
                    reasons.add("tab-complete shows " + extra + " namespaced commands the join tree leaves out (send-namespaced off or a tree filter)");
                }
            }
            // plugins that were only ever seen by name: their commands are missing from the tree and from tab-complete.
            // Libraries have no commands to hide, so they do not count; a normal server has a few command-less plugins, a
            // hiding one has almost nothing but.
            int nameOnly = 0, considered = 0;
            for (Finding f : plugins) {
                String kind = f.kind();
                if (kind.equals("library") || kind.equals("profiler")) continue;
                considered++;
                boolean names = f.has(Method.VERSION_TAB) || f.has(Method.PLUGINS_CMD);
                boolean commands = f.has(Method.TREE_NS) || f.has(Method.TAB_NS) || f.has(Method.PREFIX_TAB) || f.has(Method.COMMAND);
                if (names && !commands) nameOnly++;
            }
            if (considered >= 2 && nameOnly * 10 >= considered * 6) {
                pts += 3;
                reasons.add(nameOnly + " of " + considered + " plugins only showed up by name (/version tab-complete or the plugin list): their commands are hidden from the tree");
            }
            for (CommandAnswer a : in.commandProbes()) {
                if ((a.command().equals("plugins") || a.command().equals("pl")) && a.reply() == Reply.UNKNOWN && bukkitLike) {
                    pts += 3;
                    reasons.add("/" + a.command() + " is answered like an unknown command, but Bukkit always has it");
                    break;
                }
            }
            for (Finding f : plugins) {
                if (f.has(Method.ERROR_PROBE) && !f.has(Method.TREE_NS) && !f.has(Method.TAB_NS) && !f.has(Method.COMMAND)) {
                    pts += 2;
                    reasons.add(f.name() + " exists (its command is answered) but is not in the command tree");
                    break;
                }
            }
            ServerIntel.Snapshot s = in.intel();
            if (s.treeCount() >= 3 && s.lastTreeMs() - s.firstTreeMs() <= 20_000) {
                pts += 1;
                reasons.add("the command tree was re-sent " + s.treeCount() + " times (permission and command-filter plugins do that)");
            }
            for (Finding f : plugins) {
                if (f.kind().equals("command-filter")) {
                    pts += 2;
                    reasons.add(f.name() + " is a command-hiding plugin");
                    break;
                }
            }

            Visibility v;
            boolean pluginsSeen = !plugins.isEmpty();
            if (pts >= 5) v = Visibility.HIDDEN;
            else if (pts >= 3) v = Visibility.FILTERED;
            else if (bukkitLike || pluginsSeen) v = Visibility.OPEN;
            else v = Visibility.NONE;
            return new View(v, pts, List.copyOf(reasons), bukkitLike);
        }

        // ---- ping analysis ------------------------------------------------------------------

        static PingProfile profilePings(ServerIntel.Snapshot s) {
            List<ServerIntel.Ping> p = s.pings();
            if (s.pingTotal() < 6 || p.size() < 6) return new PingProfile(s.pingTotal(), 0, "none", 0, 0, 0);
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, steps = 0;
            boolean negShort = true;
            for (int i = 0; i < p.size(); i++) {
                int id = p.get(i).id();
                min = Math.min(min, id);
                max = Math.max(max, id);
                if (id > 0 || id < -32768) negShort = false;
                if (i > 0 && (short) (id - p.get(i - 1).id()) == -1) steps++;
            }
            double ratio = steps / (double) (p.size() - 1);
            long span = p.get(p.size() - 1).ms() - p.get(0).ms();
            double perSecond = span > 0 ? (p.size() - 1) * 1000.0 / span : 0;
            String kind = negShort && ratio >= 0.8 ? "grim" : negShort ? "negative" : "stream";
            return new PingProfile(s.pingTotal(), perSecond, kind, min, max, ratio);
        }
    }
}
