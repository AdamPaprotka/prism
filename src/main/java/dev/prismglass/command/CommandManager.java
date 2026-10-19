package dev.prismglass.command;

import dev.prismglass.Prism;
import dev.prismglass.module.Module;
import dev.prismglass.setting.ColorSetting;
import dev.prismglass.setting.Setting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.KeyUtil;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Chat commands, e.g. ".t killaura", ".set crystalaura placerange 4.5", ".bind surround z". */
public final class CommandManager {
    private final Minecraft mc = Minecraft.getInstance();
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private String prefix = ".";

    @FunctionalInterface
    private interface Handler { void run(String[] args) throws Exception; }

    private record Command(String name, String usage, String description, Handler handler, String... aliases) {}

    public CommandManager() {
        add(new Command("help", "help", "List commands.", a -> {
            ChatUtil.info("Commands (prefix §f" + prefix + "§7):");
            for (Command c : commands.values()) ChatUtil.info("§f" + prefix + c.usage() + " §8- §7" + c.description());
        }));
        add(new Command("toggle", "toggle <module>", "Toggle a module.", a -> {
            Module m = module(a, 0);
            m.toggle();
            ChatUtil.info(m.getName() + (m.isEnabled() ? " §aon" : " §coff"));
        }, "t"));
        add(new Command("bind", "bind <module> <key|none>", "Bind a key (mouse4 etc. allowed).", a -> {
            Module m = module(a, 0);
            int key = KeyUtil.fromName(arg(a, 1));
            if (key == Integer.MIN_VALUE) throw new IllegalArgumentException("Unknown key " + a[1]);
            m.bind.set(key);
            ChatUtil.good("Bound " + m.getName() + " to " + KeyUtil.name(key));
        }, "b"));
        add(new Command("set", "set <module> <setting> <value>", "Change a setting.", a -> {
            Module m = module(a, 0);
            Setting<?> s = m.getSetting(arg(a, 1));
            if (s == null) throw new IllegalArgumentException("No setting " + a[1] + " on " + m.getName());
            String value = String.join(" ", Arrays.copyOfRange(a, 2, Math.max(3, a.length)));
            if (a.length < 3) {
                ChatUtil.info(m.getName() + " " + s.getName() + " = §f" + s.display() + " §8(" + s.getDescription() + ")");
                return;
            }
            if (!s.parse(value)) throw new IllegalArgumentException("Invalid value for " + s.getName());
            ChatUtil.good(m.getName() + " " + s.getName() + " -> " + s.display());
        }, "s"));
        add(new Command("settings", "settings <module>", "Show a module's settings.", a -> {
            Module m = module(a, 0);
            ChatUtil.info("§f" + m.getName() + "§7: " + m.getDescription());
            for (Setting<?> s : m.getSettings()) {
                String v = s instanceof ColorSetting c ? c.display() : s.display();
                ChatUtil.info("  " + s.getName() + " = §f" + v);
            }
        }));
        add(new Command("reset", "reset <module>", "Reset a module's settings.", a -> {
            Module m = module(a, 0);
            for (Setting<?> s : m.getSettings()) s.reset();
            ChatUtil.good("Reset " + m.getName());
        }));
        add(new Command("drawn", "drawn <module>", "Toggle visibility in the array list.", a -> {
            Module m = module(a, 0);
            m.drawn.toggle();
            ChatUtil.good(m.getName() + " drawn: " + m.drawn.get());
        }));
        add(new Command("friend", "friend <add|del|list|clear> [name]", "Manage friends.", a -> {
            switch (arg(a, 0).toLowerCase()) {
                case "add" -> { Prism.friends().add(arg(a, 1)); ChatUtil.good("Added " + a[1]); }
                case "del", "remove" -> { Prism.friends().remove(arg(a, 1)); ChatUtil.good("Removed " + a[1]); }
                case "clear" -> { Prism.friends().clear(); ChatUtil.good("Cleared friends"); }
                default -> ChatUtil.info("Friends: §f" + String.join(", ", Prism.friends().all()));
            }
        }, "f"));
        add(new Command("config", "config <save|load|list|export|import> [name]", "Profiles; export/import share them as a code.", a -> {
            switch (arg(a, 0).toLowerCase()) {
                case "export" -> {
                    String n = a.length > 1 ? a[1] : Prism.config().getActive();
                    mc.keyboardHandler.setClipboard(Prism.config().exportCode(n));
                    ChatUtil.good("Copied profile " + n + " as a code - paste it to a friend");
                }
                case "import" -> {
                    String n = Prism.config().importCode(mc.keyboardHandler.getClipboard(), a.length > 1 ? a[1] : "shared");
                    Prism.config().switchTo(n);
                    ChatUtil.good("Imported and loaded profile " + n);
                }
                case "save" -> {
                    String n = a.length > 1 ? a[1] : Prism.config().getActive();
                    Prism.config().saveProfile(n);
                    ChatUtil.good("Saved profile " + n);
                }
                case "load" -> {
                    Prism.config().switchTo(arg(a, 1));
                    ChatUtil.good("Loaded profile " + a[1]);
                }
                default -> ChatUtil.info("Profiles: §f" + String.join(", ", Prism.config().list())
                    + " §7(active: " + Prism.config().getActive() + ")");
            }
        }, "cfg"));
        add(new Command("prefix", "prefix <char>", "Change the command prefix.", a -> {
            prefix = arg(a, 0);
            ChatUtil.good("Prefix is now " + prefix);
        }));
        add(new Command("share", "share <module...> | share paste", "Copy modules' settings as a code, or apply a pasted one.", a -> {
            if (arg(a, 0).equalsIgnoreCase("paste")) {
                List<String> done = Prism.config().importModules(mc.keyboardHandler.getClipboard(), null);
                ChatUtil.good(done.isEmpty() ? "Code has none of your modules" : "Applied settings to " + String.join(", ", done));
                return;
            }
            List<Module> mods = new java.util.ArrayList<>();
            for (int i = 0; i < a.length; i++) mods.add(module(a, i));
            mc.keyboardHandler.setClipboard(Prism.config().exportModules(mods));
            ChatUtil.good("Copied settings of " + mods.size() + " module(s) - paste with .share paste");
        }));
        add(new Command("panic", "panic", "Disable every module.", a -> {
            for (Module m : Prism.modules().all()) if (m.isEnabled()) m.setEnabled(false);
            ChatUtil.good("All modules disabled");
        }));
        add(new Command("vclip", "vclip <blocks>", "Teleport vertically.", a -> {
            double d = Double.parseDouble(arg(a, 0));
            mc.player.setPos(mc.player.getX(), mc.player.getY() + d, mc.player.getZ());
        }));
        add(new Command("hclip", "hclip <blocks>", "Teleport forward.", a -> {
            double d = Double.parseDouble(arg(a, 0));
            double yaw = Math.toRadians(mc.player.getYRot() + 90);
            mc.player.setPos(mc.player.getX() + Math.cos(yaw) * d, mc.player.getY(), mc.player.getZ() + Math.sin(yaw) * d);
        }));
        add(new Command("wp", "wp add <name> [x y z] | del <name> | list | clear", "Waypoints.", a -> {
            var wp = Prism.modules().get(dev.prismglass.module.render.Waypoints.class);
            switch (arg(a, 0).toLowerCase()) {
                case "add" -> {
                    String name = arg(a, 1);
                    int x = a.length > 4 ? Integer.parseInt(a[2]) : mc.player.getBlockX();
                    int y = a.length > 4 ? Integer.parseInt(a[3]) : mc.player.getBlockY();
                    int z = a.length > 4 ? Integer.parseInt(a[4]) : mc.player.getBlockZ();
                    wp.add(name, x, y, z);
                    if (!wp.isEnabled()) wp.setEnabled(true);
                    ChatUtil.good("Waypoint " + name + " at " + x + " " + y + " " + z);
                }
                case "del", "remove" -> ChatUtil.info(wp.remove(arg(a, 1)) ? "Removed " + a[1] : "No waypoint " + a[1]);
                case "clear" -> { wp.clear(); ChatUtil.good("Waypoints cleared"); }
                default -> {
                    if (wp.points().isEmpty()) ChatUtil.info("No waypoints here. .wp add <name>");
                    for (var p : wp.points()) ChatUtil.info("§f" + p.name() + " §7" + p.x() + " " + p.y() + " " + p.z() + " (" + p.dim() + ")");
                }
            }
        }, "waypoint", "waypoints"));
        add(new Command("pearltest", "pearltest [distance]", "Singleplayer: an enemy pearl is thrown next to you (PearlPredict / AutoPearl test).", a -> {
            double dist = a.length > 0 ? Double.parseDouble(a[0]) : 25;
            Prism.modules().get(dev.prismglass.module.render.PearlPredict.class).spawnTestPearl(dist);
        }, "pt"));
        add(new Command("about", "about", "Version, release channel and build/debug info.", a ->
            mc.schedule(() -> mc.setScreen(new dev.prismglass.gui.AboutScreen(null))), "version"));
        add(new Command("changelog", "changelog", "Show what's new in Prism.", a ->
            mc.schedule(() -> mc.setScreen(new dev.prismglass.gui.ChangelogScreen(null)))));
        add(new Command("hud", "hud", "Open the HUD editor (drag elements with the mouse).", a ->
            mc.schedule(() -> mc.setScreen(new dev.prismglass.gui.HudEditorScreen(null)))));
        add(new Command("elytrabot", "elytrabot <x> <z> | stop", "Fly with elytra to coordinates.", a -> {
            var bot = Prism.modules().get(dev.prismglass.module.movement.ElytraBot.class);
            if (arg(a, 0).equalsIgnoreCase("stop")) { bot.setEnabled(false); ChatUtil.good("ElytraBot stopped"); return; }
            double x = Double.parseDouble(arg(a, 0)), z = Double.parseDouble(arg(a, 1));
            bot.goTo(x, z);
            ChatUtil.good(String.format("ElytraBot: flying to %.0f, %.0f", x, z));
        }, "eb"));
        add(new Command("staff", "staff <add|del|list|clear> [name]", "StaffWatcher's staff list; list also shows who is online.", a -> {
            var sw = Prism.modules().get(dev.prismglass.module.client.StaffWatcher.class);
            switch (arg(a, 0).toLowerCase()) {
                case "add" -> { sw.addStaff(arg(a, 1)); ChatUtil.good("Staff: added " + a[1]); }
                case "del", "remove" -> { sw.removeStaff(arg(a, 1)); ChatUtil.good("Staff: removed " + a[1]); }
                case "clear" -> { sw.staff.parse(""); ChatUtil.good("Staff list cleared"); }
                default -> {
                    ChatUtil.info("Staff list: §f" + (sw.staffList().isEmpty() ? "empty" : String.join(", ", sw.staffList())));
                    if (!sw.isEnabled()) ChatUtil.info("StaffWatcher is off");
                    else ChatUtil.info("Staff online: §f" + (sw.onlineStaff().isEmpty() ? "none" : String.join(", ", sw.onlineStaff())));
                }
            }
        }));
        add(new Command("spamdupe", "spamdupe [seconds] | stop", "Servers with /dupe: fill your inventory with what you hold, throw it up, repeat (default: for the item pickup delay).", a -> {
            var dupe = Prism.modules().get(dev.prismglass.module.anarchy.SpamDupe.class);
            if (arg(a, 0).equalsIgnoreCase("stop")) { dupe.setEnabled(false); return; }
            double seconds = a.length > 0 ? Double.parseDouble(a[0]) : 0;
            dupe.start(seconds);
            if (dupe.isEnabled()) ChatUtil.good("SpamDupe: running for " + (seconds > 0 ? seconds : dupe.pickupDelay.get()) + "s");
        }));
        add(new Command("seed", "seed <seed|clear>", "World seed for Xray seed mode.", a -> {
            var xray = Prism.modules().get(dev.prismglass.module.render.Xray.class);
            if (a.length == 0) { ChatUtil.info("Seed: §f" + (xray.seed.get().isEmpty() ? "none" : xray.seed.get())); return; }
            String value = String.join(" ", a);
            if (value.equalsIgnoreCase("clear")) { xray.seed.set(""); ChatUtil.good("Seed cleared"); return; }
            xray.seed.set(value);
            xray.mode.parse("Seed");
            if (!xray.isEnabled()) xray.toggle();
            ChatUtil.good("Seed set to " + dev.prismglass.module.render.Xray.parseSeed(value) + " - Xray is in Seed mode");
        }));
        add(new Command("plugins", "plugins [probe|last]", "Scan the server's plugins (probe also runs /bukkit:plugins; last reprints the result).", a -> {
            var scanner = Prism.modules().get(dev.prismglass.module.world.PluginScanner.class);
            if (!scanner.isEnabled()) scanner.setEnabled(true);
            String what = a.length > 0 ? a[0].toLowerCase() : "";
            if (what.equals("last")) {
                var list = scanner.plugins();
                ChatUtil.info("Server brand: §f" + (scanner.brand() == null ? "unknown" : scanner.brand()));
                ChatUtil.info(list.isEmpty() ? "No plugins detected yet" : "Plugins (" + list.size() + "): §f" + String.join(", ", list));
                return;
            }
            scanner.scan(what.equals("probe"));
            ChatUtil.info("Scanning plugins...");
        }, "pl", "pluginscan"));
        add(new Command("joinplayer", "joinplayer [name]", "A real second player: its own connection to this server, logged in as a cracked account (offline-mode servers).", a -> {
            String name = a.length > 0 ? a[0] : Prism.fakePlayers().randomName();
            String err = Prism.bots().join(name);
            if (err != null) ChatUtil.error(err);
            else ChatUtil.info("Bot §f" + name + "§7 is connecting" + (Prism.proxies().active() != null ? " through " + Prism.proxies().active().name() : ""));
        }, "bot", "jp"));
        add(new Command("leaveplayer", "leaveplayer <name|all>", "Disconnect a bot.", a -> {
            if (a.length == 0 || a[0].equalsIgnoreCase("all")) {
                if (Prism.bots().names().isEmpty()) ChatUtil.error("No bots online");
                else Prism.bots().leaveAll();
                return;
            }
            String err = Prism.bots().leave(a[0]);
            if (err != null) ChatUtil.error(err);
        }, "lp"));
        add(new Command("switchplayer", "switchplayer <bot> [drop]", "Become that bot: your session switches to its cracked account and reconnects; a bot takes your old name unless you say drop.", a -> {
            String err = Prism.bots().switchTo(arg(a, 0), !(a.length > 1 && a[1].equalsIgnoreCase("drop")));
            if (err != null) ChatUtil.error(err);
        }, "switch", "swp"));
        add(new Command("sayplayer", "sayplayer <name|all> <message>", "Chat as a bot; a message starting with / runs as a command.", a -> {
            String msg = String.join(" ", Arrays.copyOfRange(a, 1, Math.max(2, a.length))).trim();
            if (msg.isEmpty()) throw new IllegalArgumentException("Missing message");
            String err = Prism.bots().say(arg(a, 0), msg);
            if (err != null) ChatUtil.error(err);
        }, "sp", "botsay"));
        add(new Command("fakeplayer", "fakeplayer [name]", "Client-side dummy (your skin and gear) next to you; nobody else sees it. Target practice.", a -> {
            String name = a.length > 0 ? a[0] : Prism.fakePlayers().randomName();
            String err = Prism.fakePlayers().join(name);
            if (err != null) ChatUtil.error(err);
        }, "fakejoin"));
        add(new Command("fakeleave", "fakeleave [name]", "Remove a client-side dummy (no name = the last one).", a -> {
            if (a.length == 0) {
                String last = Prism.fakePlayers().leaveLast();
                if (last == null) ChatUtil.error("No fake players around");
                return;
            }
            String err = Prism.fakePlayers().leave(a[0]);
            if (err != null) ChatUtil.error(err);
        }));
        add(new Command("account", "account [add|del|login|original|random|list] [name]", "Cracked accounts; no argument opens the Accounts screen.", a -> {
            var am = Prism.accounts();
            if (a.length == 0) { mc.schedule(() -> mc.setScreen(new dev.prismglass.gui.AccountsScreen(null))); return; }
            switch (a[0].toLowerCase()) {
                case "add" -> ChatUtil.info(am.add(arg(a, 1)) ? "Added " + a[1] : "Invalid name or already saved");
                case "del", "remove" -> ChatUtil.info(am.remove(arg(a, 1)) ? "Removed " + a[1] : "No account " + a[1]);
                case "login", "use" -> {
                    if (!am.login(arg(a, 1))) throw new IllegalArgumentException("Names are 1-16 letters, digits or _");
                    ChatUtil.good("Logged in as " + a[1] + " (cracked)" + (mc.player != null ? ", applies when you next join a server" : ""));
                }
                case "original", "premium" -> { am.loginOriginal(); ChatUtil.good("Back to " + am.originalName()); }
                case "random" -> {
                    String n = dev.prismglass.manager.AccountManager.randomName();
                    am.login(n);
                    ChatUtil.good("Logged in as " + n + " (cracked)");
                }
                default -> {
                    ChatUtil.info("Logged in as §f" + am.currentName() + " §7(" + (am.isOriginal() ? "original" : "cracked") + ")");
                    ChatUtil.info("Accounts: §f" + (am.all().isEmpty() ? "none" : String.join(", ", am.all())));
                }
            }
        }, "acc", "accounts", "login"));
        add(new Command("proxy", "proxy [list|add <name> <host:port> [socks5|socks4|http] [user pass]|del <name>|use <name>|off|tor [start|stop|new]|ip|account <account> <proxy|none>]",
            "Proxies for the server connection; no argument opens the Proxies screen. Tor starts tor.exe for you.", a -> {
            var pm = Prism.proxies();
            if (a.length == 0) { mc.schedule(() -> mc.setScreen(new dev.prismglass.gui.ProxiesScreen(null))); return; }
            switch (a[0].toLowerCase()) {
                case "add" -> {
                    pm.put(dev.prismglass.manager.ProxyManager.parse(arg(a, 1), arg(a, 2), a.length > 3 ? a[3] : null, a.length > 4 ? a[4] : null, a.length > 5 ? a[5] : null));
                    ChatUtil.good("Added proxy " + a[1] + " - .proxy use " + a[1]);
                }
                case "del", "remove" -> ChatUtil.info(pm.remove(arg(a, 1)) ? "Removed " + a[1] : "No proxy " + a[1] + " (Tor can't be removed)");
                case "use" -> {
                    if (!pm.use(arg(a, 1))) throw new IllegalArgumentException("No proxy named " + a[1]);
                    ChatUtil.good("Next connections go through " + pm.active().name() + (pm.active().isTor() ? " - " + pm.tor().status() : ""));
                }
                case "off", "direct" -> { pm.use(null); ChatUtil.good("No proxy: connecting directly"); }
                case "tor" -> {
                    var tor = pm.tor();
                    switch (a.length > 1 ? a[1].toLowerCase() : "status") {
                        case "start" -> ChatUtil.info(tor.start() ? "Tor starting (" + tor.status() + ")" : tor.status());
                        case "stop" -> { tor.stop(); ChatUtil.info("Tor stopped"); }
                        case "new", "newnym", "identity" -> ChatUtil.info(tor.newIdentity() ? "New Tor circuit requested" : tor.status());
                        case "install", "guide" -> ChatUtil.info(dev.prismglass.manager.TorRunner.openInstallGuide() ? "Opened prism/torinstall.txt" : "Could not open the guide");
                        default -> ChatUtil.info("Tor: §f" + tor.status() + " §7(" + tor.bootstrap() + "%), tor.exe " + (dev.prismglass.manager.TorRunner.findTor() == null ? "§cnot found" : "found"));
                    }
                }
                case "ip" -> {
                    var p = pm.active();
                    ChatUtil.info(p == null ? "Checking your direct IP..." : "Checking the IP seen through " + p.name() + "...");
                    Thread t = new Thread(() -> {
                        try {
                            String ip = dev.prismglass.manager.ProxyManager.publicIp(p);
                            if (p == null) { mc.execute(() -> ChatUtil.info("Direct IP: §f" + ip)); return; }
                            String direct;
                            try { direct = dev.prismglass.manager.ProxyManager.publicIp(null); } catch (Exception e) { direct = null; }
                            String note = direct == null ? "" : direct.equals(ip) ? " §c(same as without the proxy!)" : " §a(different from your real IP)";
                            mc.execute(() -> ChatUtil.good("Through " + p.name() + ": §f" + ip + note));
                        } catch (Exception e) {
                            mc.execute(() -> ChatUtil.error("IP check failed: " + e.getMessage()));
                        }
                    }, "prism-ipcheck");
                    t.setDaemon(true);
                    t.start();
                }
                case "account" -> {
                    String acc = arg(a, 1), px = arg(a, 2);
                    if (px.equalsIgnoreCase("none")) { pm.setAccountProxy(acc, null); ChatUtil.good(acc + " uses no fixed proxy"); }
                    else if (pm.get(px) == null) throw new IllegalArgumentException("No proxy named " + px);
                    else { pm.setAccountProxy(acc, pm.get(px).name()); ChatUtil.good("Logging in as " + acc + " now switches to " + pm.get(px).name()); }
                }
                default -> {
                    var act = pm.active();
                    ChatUtil.info("Active: §f" + (act == null ? "none (direct)" : act.name() + " " + act.address()));
                    for (var p : pm.all()) ChatUtil.info("  §f" + p.name() + " §7" + p.type().name().toLowerCase() + " " + p.address()
                        + (p.isTor() ? " - " + pm.tor().status() : "") + (pm.accountProxy("") == null ? "" : ""));
                }
            }
        }, "proxies", "px"));
        add(new Command("coords", "coords", "Copy your coordinates.", a -> {
            String c = String.format("%d %d %d", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
            mc.keyboardHandler.setClipboard(c);
            ChatUtil.good("Copied " + c);
        }));
    }

    private void add(Command c) {
        commands.put(c.name(), c);
        for (String alias : c.aliases()) commands.putIfAbsent(alias, c);
    }

    private static String arg(String[] a, int i) {
        if (i >= a.length) throw new IllegalArgumentException("Missing argument");
        return a[i];
    }

    private static Module module(String[] a, int i) {
        Module m = Prism.modules().get(arg(a, i));
        if (m == null) throw new IllegalArgumentException("No module named " + a[i]);
        return m;
    }

    public String getPrefix() { return prefix; }
    public void setPrefix(String p) { prefix = p; }

    /** What the chat box should offer for {@code text} (the input up to the cursor): options replace text from {@code start}. */
    public record Completion(int start, List<String> options, String usage, String description, boolean error) {}

    private static final List<String> KEYS = new java.util.ArrayList<>(List.of("none", "mouse3", "mouse4", "mouse5",
        "left.shift", "right.shift", "left.control", "right.control", "left.alt", "right.alt", "tab", "caps.lock", "grave.accent",
        "insert", "delete", "home", "end", "page.up", "page.down", "up", "down", "left", "right", "backspace", "enter", "space"));
    static {
        for (char c = 'a'; c <= 'z'; c++) KEYS.add(String.valueOf(c));
        for (char c = '0'; c <= '9'; c++) KEYS.add(String.valueOf(c));
        for (int i = 1; i <= 12; i++) KEYS.add("f" + i);
        for (int i = 0; i <= 9; i++) KEYS.add("keypad." + i);
    }

    /** @return null when the text isn't a Prism command. */
    public Completion complete(String text) {
        if (prefix.isEmpty() || !text.startsWith(prefix)) return null;
        String body = text.substring(prefix.length());
        int lastSpace = body.lastIndexOf(' ');
        String partial = body.substring(lastSpace + 1);
        int start = prefix.length() + lastSpace + 1;
        String head = lastSpace < 0 ? "" : body.substring(0, lastSpace).trim();
        String[] done = head.isEmpty() ? new String[0] : head.split("\\s+");
        if (done.length == 0) {
            // the command itself: real names first, aliases only once you've typed something
            List<String> names = new java.util.ArrayList<>();
            for (var e : commands.entrySet()) if (e.getKey().equals(e.getValue().name()) || !partial.isEmpty()) names.add(e.getKey());
            Command exact = commands.get(partial.toLowerCase());
            return new Completion(start, filter(names, partial), exact == null ? null : prefix + exact.usage(),
                exact == null ? null : exact.description(), false);
        }
        Command c = commands.get(done[0].toLowerCase());
        if (c == null) return new Completion(start, List.of(), "Unknown command " + done[0], null, true);
        String[] args = Arrays.copyOfRange(done, 1, done.length);
        List<String> options;
        try { options = argOptions(c.name(), args); } catch (RuntimeException e) { options = List.of(); }
        return new Completion(start, filter(options, partial), prefix + c.usage(), c.description(), false);
    }

    private List<String> argOptions(String cmd, String[] a) {
        int i = a.length;
        return switch (cmd) {
            case "toggle", "settings", "reset", "drawn" -> i == 0 ? moduleNames() : List.of();
            case "share" -> {
                List<String> l = new java.util.ArrayList<>(moduleNames());
                if (i == 0) l.add(0, "paste");
                yield l;
            }
            case "bind" -> i == 0 ? moduleNames() : i == 1 ? KEYS : List.of();
            case "set" -> {
                if (i == 0) yield moduleNames();
                Module m = Prism.modules().get(a[0]);
                if (m == null) yield List.of();
                if (i == 1) yield m.getSettings().stream().map(Setting::getName).toList();
                Setting<?> s = m.getSetting(a[1]);
                yield i == 2 && s != null ? settingValues(s) : List.of();
            }
            case "friend" -> {
                if (i == 0) yield List.of("add", "del", "list", "clear");
                if (i > 1) yield List.of();
                yield switch (a[0].toLowerCase()) {
                    case "add" -> playerNames().stream().filter(n -> !Prism.friends().isFriend(n)).toList();
                    case "del", "remove" -> new java.util.ArrayList<>(Prism.friends().all());
                    default -> List.of();
                };
            }
            case "config" -> i == 0 ? List.of("save", "load", "list", "export", "import")
                : i == 1 && List.of("save", "load", "export").contains(a[0].toLowerCase()) ? Prism.config().list() : List.of();
            case "wp" -> {
                if (i == 0) yield List.of("add", "del", "list", "clear");
                if (i == 1 && (a[0].equalsIgnoreCase("del") || a[0].equalsIgnoreCase("remove")))
                    yield Prism.modules().get(dev.prismglass.module.render.Waypoints.class).points().stream().map(p -> p.name()).toList();
                yield List.of();
            }
            case "elytrabot" -> i == 0 ? List.of("stop") : List.of();
            case "seed" -> i == 0 ? List.of("clear") : List.of();
            case "plugins" -> i == 0 ? List.of("probe", "last") : List.of();
            case "leaveplayer" -> { if (i != 0) yield List.of(); List<String> l = new java.util.ArrayList<>(Prism.bots().names()); l.add("all"); yield l; }
            case "sayplayer" -> { if (i != 0) yield List.of("/"); List<String> l = new java.util.ArrayList<>(Prism.bots().names()); l.add("all"); yield l; }
            case "fakeleave" -> i == 0 ? Prism.fakePlayers().names() : List.of();
            case "switchplayer" -> i == 0 ? Prism.bots().names() : i == 1 ? List.of("drop") : List.of();
            case "proxy" -> {
                if (i == 0) yield List.of("list", "add", "del", "use", "off", "tor", "ip", "account");
                List<String> names = Prism.proxies().all().stream().map(p -> p.name()).toList();
                if (i == 1 && (a[0].equalsIgnoreCase("use") || a[0].equalsIgnoreCase("del"))) yield names;
                if (i == 1 && a[0].equalsIgnoreCase("tor")) yield List.of("start", "stop", "new", "install", "status");
                if (i == 1 && a[0].equalsIgnoreCase("account")) yield Prism.accounts().all();
                if (i == 2 && a[0].equalsIgnoreCase("account")) { List<String> l = new java.util.ArrayList<>(names); l.add("none"); yield l; }
                if (i == 3 && a[0].equalsIgnoreCase("add")) yield List.of("socks5", "socks4", "http");
                yield List.of();
            }
            case "account" -> {
                if (i == 0) yield List.of("add", "del", "login", "original", "random", "list");
                if (i == 1 && (a[0].equalsIgnoreCase("login") || a[0].equalsIgnoreCase("del"))) yield Prism.accounts().all();
                yield List.of();
            }
            case "staff" -> {
                if (i == 0) yield List.of("add", "del", "list", "clear");
                if (i > 1) yield List.of();
                var sw = Prism.modules().get(dev.prismglass.module.client.StaffWatcher.class);
                yield switch (a[0].toLowerCase()) {
                    case "add" -> playerNames().stream().filter(n -> !sw.onList(n)).toList();
                    case "del", "remove" -> sw.staffList();
                    default -> List.of();
                };
            }
            case "spamdupe" -> i == 0 ? List.of("stop", "2", "5", "10") : List.of();
            default -> List.of();
        };
    }

    private static List<String> moduleNames() {
        return Prism.modules().all().stream().map(Module::getName).toList();
    }

    private List<String> playerNames() {
        if (mc.getConnection() == null) return List.of();
        String self = mc.player == null ? "" : mc.player.getGameProfile().name();
        return mc.getConnection().getOnlinePlayers().stream().map(p -> p.getProfile().name()).filter(n -> !n.equals(self)).sorted().toList();
    }

    private static List<String> settingValues(Setting<?> s) {
        if (s instanceof dev.prismglass.setting.BoolSetting) return List.of("true", "false");
        if (s instanceof dev.prismglass.setting.ModeSetting m) return m.getModes();
        if (s instanceof dev.prismglass.setting.KeySetting) return KEYS;
        if (s instanceof ColorSetting) return List.of("rainbow", "#FFFFFFFF");
        if (s instanceof dev.prismglass.setting.NumberSetting n) {
            java.util.LinkedHashSet<String> v = new java.util.LinkedHashSet<>();
            v.add(n.display());
            v.add(new dev.prismglass.setting.NumberSetting("", "", n.getDefault(), n.getMin(), n.getMax(), n.getStep()).display());
            return new java.util.ArrayList<>(v);
        }
        return List.of(s.display());
    }

    /** Case-insensitive: everything starting with the typed text, or (when nothing does) everything containing it.
     *  Brigadier sorts the list alphabetically, so mixing both would bury the real matches. */
    private static List<String> filter(List<String> options, String partial) {
        String p = partial.toLowerCase();
        List<String> starts = new java.util.ArrayList<>(), contains = new java.util.ArrayList<>();
        for (String o : options) {
            String l = o.toLowerCase();
            if (l.startsWith(p)) starts.add(o);
            else if (!p.isEmpty() && l.contains(p)) contains.add(o);
        }
        return starts.isEmpty() ? contains : starts;
    }

    /** @return true if the message was a command and must not be sent to the server. */
    public boolean handle(String message) {
        if (!message.startsWith(prefix)) return false;
        String[] parts = message.substring(prefix.length()).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return true;
        Command c = commands.get(parts[0].toLowerCase());
        if (c == null) {
            ChatUtil.error("Unknown command. Try " + prefix + "help");
            return true;
        }
        try {
            c.handler().run(Arrays.copyOfRange(parts, 1, parts.length));
        } catch (Exception e) {
            ChatUtil.error(e.getMessage() == null ? e.toString() : e.getMessage());
            ChatUtil.info("Usage: " + prefix + c.usage());
        }
        return true;
    }
}
