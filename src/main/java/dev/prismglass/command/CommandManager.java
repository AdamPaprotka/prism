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

    private record Command(String name, String usage, String description, dev.prismglass.api.CommandHandler handler, String... aliases) {}

    /** Tab completion of addon commands (built-in ones are in argOptions). */
    private final Map<String, java.util.function.Function<String[], List<String>>> completers = new java.util.HashMap<>();

    /** Addon API: a new command. An existing name is refused; aliases already taken are skipped. */
    public void register(String name, String usage, String description, dev.prismglass.api.CommandHandler handler,
                         java.util.function.Function<String[], List<String>> completer, String... aliases) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (commands.containsKey(key)) throw new IllegalArgumentException("Prism already has a command ." + name);
        add(new Command(key, usage, description, handler, aliases));
        if (completer != null) completers.put(key, completer);
    }

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
        add(new Command("pingspike", "pingspike [ticks]", "Lag switch: hold ALL packets in and out. No argument toggles; with ticks it releases after that many ticks.", a -> {
            var spike = Prism.modules().get(dev.prismglass.module.movement.PingSpike.class);
            if (mc.player == null || mc.level == null) throw new IllegalStateException("Join a world first");
            if (a.length == 0) {
                if (spike.isEnabled()) spike.stop(); else spike.start(0);
                return;
            }
            int ticks;
            try { ticks = Integer.parseInt(a[0]); } catch (NumberFormatException e) { throw new IllegalArgumentException("Ticks must be a whole number"); }
            if (ticks < 1 || ticks > 1200) throw new IllegalArgumentException("Ticks must be 1-1200");
            spike.start(ticks);
        }, "ps"));
        add(new Command("panic", "panic", "Disable every module.", a -> {
            for (Module m : Prism.modules().all()) if (m.isEnabled() && !(m instanceof dev.prismglass.module.Service)) m.setEnabled(false);
            ChatUtil.good("All modules disabled (services keep running)");
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
        add(new Command("marker", "marker add <name> circle|sphere [x y z] <radius> | add <name> cuboid <x1 y1 z1 x2 y2 z2> | del <name> | list | clear", "Markers: outlines to plan builds.", a -> {
            var mk = Prism.modules().get(dev.prismglass.module.render.Markers.class);
            switch (arg(a, 0).toLowerCase()) {
                case "add" -> {
                    String name = arg(a, 1), type = arg(a, 2).toLowerCase();
                    if (type.equals("cuboid")) {
                        if (a.length < 9) throw new IllegalArgumentException("Usage: .marker add <name> cuboid x1 y1 z1 x2 y2 z2");
                        mk.addCuboid(name, Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6]), Double.parseDouble(a[7]), Double.parseDouble(a[8]));
                    } else if (type.equals("circle") || type.equals("sphere")) {
                        // ".marker add n circle 5" = centred on you; ".marker add n circle x y z 5" = there
                        double x = mc.player.getBlockX() + 0.5, y = mc.player.getY(), z = mc.player.getBlockZ() + 0.5, r;
                        if (a.length >= 7) { x = Double.parseDouble(a[3]); y = Double.parseDouble(a[4]); z = Double.parseDouble(a[5]); r = Double.parseDouble(a[6]); }
                        else r = Double.parseDouble(arg(a, 3));
                        mk.addSphere(name, type.equals("sphere"), x, y, z, r);
                    } else throw new IllegalArgumentException("Type must be circle, sphere or cuboid");
                    if (!mk.isEnabled()) mk.setEnabled(true);
                    ChatUtil.good("Marker " + name + " (" + type + ")");
                }
                case "del", "remove" -> ChatUtil.info(mk.remove(arg(a, 1)) ? "Removed " + a[1] : "No marker " + a[1]);
                case "clear" -> { mk.clear(); ChatUtil.good("Markers cleared"); }
                default -> {
                    if (mk.markers().isEmpty()) ChatUtil.info("No markers here. .marker add <name> circle <radius>");
                    for (var m : mk.markers()) ChatUtil.info("§f" + m.name() + " §7" + m.type() + " (" + m.dim() + ")");
                }
            }
        }, "markers", "mk"));
        add(new Command("ai", "ai [player] | list | forget <player>", "Damage AI Learn: what it learned about a player.", a -> {
            var ai = Prism.modules().get(dev.prismglass.module.service.DamageAILearn.class);
            if (a.length == 0 || arg(a, 0).equalsIgnoreCase("list")) {
                var names = ai.known();
                ChatUtil.info(names.isEmpty() ? "AI: nobody fought yet" : "AI knows (" + names.size() + "): " + String.join(", ", names));
                if (!ai.isEnabled()) ChatUtil.info("Turn it on in ClickGUI -> Config -> Services");
                return;
            }
            if (arg(a, 0).equalsIgnoreCase("forget")) { ChatUtil.info(ai.forget(arg(a, 1)) ? "Forgot " + a[1] : "No profile for " + a[1]); return; }
            ChatUtil.info("AI " + ai.summary(a[0]));
        }));
        add(new Command("follow", "follow <player> | off", "Follow: walk after a player.", a -> {
            var f = Prism.modules().get(dev.prismglass.module.movement.Follow.class);
            if (a.length == 0 || arg(a, 0).equalsIgnoreCase("off")) { if (f.isEnabled()) f.toggle(); ChatUtil.info("Follow off"); return; }
            f.follow(a[0]);
        }));
        add(new Command("pearltest", "pearltest [distance]", "Singleplayer: an enemy pearl is thrown next to you (PearlPredict / AutoPearl test).", a -> {
            double dist = a.length > 0 ? Double.parseDouble(a[0]) : 25;
            Prism.modules().get(dev.prismglass.module.render.PearlPredict.class).spawnTestPearl(dist);
        }, "pt"));
        add(new Command("about", "about", "Version, release channel and build/debug info.", a ->
            mc.schedule(() -> mc.gui.setScreen(new dev.prismglass.gui.AboutScreen(null))), "version"));
        add(new Command("changelog", "changelog", "Show what's new in Prism.", a ->
            mc.schedule(() -> mc.gui.setScreen(new dev.prismglass.gui.ChangelogScreen(null)))));
        add(new Command("hud", "hud", "Open the HUD editor (drag elements with the mouse).", a ->
            mc.schedule(() -> mc.gui.setScreen(new dev.prismglass.gui.HudEditorScreen(null)))));
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
        add(new Command("spamdupe", "spamdupe [cycles] | stop", "Servers with /dupe: fill your inventory with what you hold, throw it up or put it in chests (Output), repeat for N cycles.", a -> {
            var dupe = Prism.modules().get(dev.prismglass.module.anarchy.SpamDupe.class);
            if (arg(a, 0).equalsIgnoreCase("stop")) { dupe.setEnabled(false); return; }
            int cycles;
            try { cycles = a.length > 0 ? Integer.parseInt(a[0]) : 0; }
            catch (NumberFormatException e) { ChatUtil.error("Usage: .spamdupe [cycles] | stop"); return; }
            dupe.start(cycles);
            if (dupe.isEnabled()) ChatUtil.good("SpamDupe: " + (cycles > 0 ? cycles : dupe.cyclesSetting.getInt()) + " cycles, " + dupe.output.get());
        }));
        add(new Command("dupe", "dupe <preset> [command] | list | stop", "PluginDupe: run a plugin dupe race (VaultKick, ShulkerKick, MountKick...). The command fills {cmd}, e.g. .dupe vaultkick pv 2.", a -> {
            var dupe = Prism.modules().get(dev.prismglass.module.anarchy.PluginDupe.class);
            String what = arg(a, 0);
            if (what.equalsIgnoreCase("stop")) { dupe.setEnabled(false); return; }
            if (what.isEmpty() || what.equalsIgnoreCase("list")) {
                for (var e : dev.prismglass.module.anarchy.PluginDupe.ABOUT.entrySet())
                    ChatUtil.info("§f" + e.getKey() + " §7" + e.getValue());
                return;
            }
            String preset = null;
            for (String p : dev.prismglass.module.anarchy.PluginDupe.PRESETS.keySet()) if (p.equalsIgnoreCase(what)) preset = p;
            if (preset == null) { ChatUtil.error("Unknown preset " + what + " (.dupe list)"); return; }
            dupe.start(preset, a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null);
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
        add(new Command("structurefinder", "structurefinder find <structure|biome_<biome>> | stop", "Marks the nearest structure or biome (needs the seed); the marker goes away within 50m.", a -> {
            var sf = Prism.modules().get(dev.prismglass.module.world.StructureFinder.class);
            switch (arg(a, 0).toLowerCase()) {
                case "find" -> sf.find(arg(a, 1));
                case "stop", "clear" -> sf.stopFind();
                default -> ChatUtil.error("Usage: " + prefix + "structurefinder find <structure> | stop");
            }
        }, "sf", "structure"));
        add(new Command("crack", "crack [reset|retry]", "SeedCracker status; reset forgets this server's data, retry searches again.", a -> {
            var cracker = Prism.modules().get(dev.prismglass.module.world.SeedCracker.class);
            String what = a.length > 0 ? a[0].toLowerCase() : "";
            if (what.equals("reset")) { cracker.reset(); ChatUtil.good("SeedCracker: data for this server cleared"); return; }
            if (what.equals("retry")) { cracker.retry(); ChatUtil.good("SeedCracker: searching again"); return; }
            if (!cracker.isEnabled()) ChatUtil.info("SeedCracker is off (turn it on to collect)");
            for (String line : cracker.describe()) ChatUtil.info(line);
        }));
        add(new Command("plugins", "plugins [probe|deep|last|why [name]|reload]", "Scan the server's plugins and whether it hides them (probe also runs /bukkit:plugins; deep adds a letter sweep and the error probe, which run real commands; last reprints; why shows the evidence).", a -> {
            var scanner = Prism.modules().get(dev.prismglass.module.world.PluginScanner.class);
            String what = a.length > 0 ? a[0].toLowerCase() : "";
            if (what.equals("reload")) {
                var sigs = dev.prismglass.module.world.PluginScanner.reloadSignatures();
                ChatUtil.good("Signatures: " + sigs.size() + " (" + sigs.bundledCount() + " bundled, " + sigs.userCount() + " from prism/plugin_signatures.json)");
                for (String p : sigs.problems()) ChatUtil.error(p);
                return;
            }
            if (!scanner.isEnabled()) scanner.setEnabled(true);
            if (what.equals("why")) { scanner.why(a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null); return; }
            if (what.equals("last")) {
                var r = scanner.recompute();
                ChatUtil.info("Server brand: §f" + (scanner.brand() == null ? "unknown" : scanner.brand()) + " §7- commands " + r.view.verdict().label);
                var list = scanner.plugins();
                ChatUtil.info(list.isEmpty() ? "No plugins detected yet" : "Plugins (" + list.size() + "): §f" + String.join(", ", list));
                return;
            }
            scanner.scan(what.equals("probe") || what.equals("deep"), what.equals("deep"));
            ChatUtil.info("Scanning plugins...");
        }, "pl", "pluginscan"));
        add(new Command("joinplayer", "joinplayer [name] [true|false]", "A real second player: its own connection to this server, logged in as a cracked account (offline-mode servers). true = join from a new IP (its own Tor circuit).", a -> {
            boolean flagOnly = a.length > 0 && (a[0].equalsIgnoreCase("true") || a[0].equalsIgnoreCase("false"));
            String name = a.length > 0 && !flagOnly ? a[0] : Prism.fakePlayers().randomName();
            boolean newIp = a.length > 0 && a[a.length - 1].equalsIgnoreCase("true");
            String err = Prism.bots().join(name, newIp);
            if (err != null) ChatUtil.error(err);
            else if (newIp) ChatUtil.info("Bot \u00a7f" + name + "\u00a77 is connecting through Tor on its own circuit (new IP)" + (Prism.proxies().tor().ready() ? "" : ", waiting for Tor to start"));
            else ChatUtil.info("Bot \u00a7f" + name + "\u00a77 is connecting" + (Prism.proxies().active() != null ? " through " + Prism.proxies().active().name() : ""));
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
        add(new Command("switchplayer", "switchplayer <bot> [drop] [fast]", "Become that bot: your session switches to its cracked account. Add \"fast\" for the experimental seamless switch (no relog); a bot takes your old name unless you say drop.", a -> {
            boolean drop = false, fast = false;
            for (int i = 1; i < a.length; i++) { if (a[i].equalsIgnoreCase("drop")) drop = true; else if (a[i].equalsIgnoreCase("fast")) fast = true; }
            String err = fast ? Prism.bots().fastSwitch(arg(a, 0), !drop) : Prism.bots().switchTo(arg(a, 0), !drop);
            if (err != null) ChatUtil.error(err);
        }, "switch", "swp"));
        add(new Command("sayplayer", "sayplayer <name|all> <message>", "Chat as a bot; a message starting with / runs as a command.", a -> {
            String msg = String.join(" ", Arrays.copyOfRange(a, 1, Math.max(2, a.length))).trim();
            if (msg.isEmpty()) throw new IllegalArgumentException("Missing message");
            String err = Prism.bots().say(arg(a, 0), msg);
            if (err != null) ChatUtil.error(err);
        }, "sp", "botsay"));
        add(new Command("playerfollow", "playerfollow <bot|all> <player|off>", "Make your bot(s) walk after a player (or off). The target must be loaded near the bot.", a -> {
            if (a.length < 2) throw new IllegalArgumentException("Usage: .playerfollow <bot|all> <player|off>");
            String err = a[1].equalsIgnoreCase("off") ? Prism.bots().stopFollow(a[0]) : Prism.bots().follow(a[0], a[1]);
            if (err != null) ChatUtil.error(err);
            else ChatUtil.info(a[1].equalsIgnoreCase("off") ? "§f" + a[0] + "§7 stopped following" : "§f" + a[0] + "§7 following §f" + a[1]);
        }, "pf", "botfollow"));
        add(new Command("playerdrop", "playerdrop <bot|all> <slot 1-9|all> [qty|max]", "Drop items from your bot(s): a hotbar slot 1-9 or all, a quantity or max (default max).", a -> {
            if (a.length < 2) throw new IllegalArgumentException("Usage: .playerdrop <bot|all> <slot 1-9|all> [qty|max]");
            String err = Prism.bots().drop(a[0], a[1], a.length > 2 ? a[2] : "max");
            if (err != null) ChatUtil.error(err);
            else ChatUtil.info("§f" + a[0] + "§7 dropped §f" + a[1]);
        }, "pd", "botdrop"));
        add(new Command("viewplayerbotinventory", "viewplayerbotinventory <bot>", "Show what one of your bots is holding (no switching).", a -> {
            if (a.length < 1) throw new IllegalArgumentException("Usage: .viewplayerbotinventory <bot>");
            for (String line : Prism.bots().inventory(a[0])) ChatUtil.info(line);
        }, "botinv", "vpbi"));
        add(new Command("goto", "goto <player>", "Teleport to a loaded player via position packets. [no-anticheat servers only; Grim/NCP will setback]", a -> {
            if (a.length < 1) throw new IllegalArgumentException("Usage: .goto <player>");
            if (mc.player == null || mc.level == null) { ChatUtil.error("Join a world first"); return; }
            net.minecraft.world.entity.player.Player target = null;
            double best = Double.MAX_VALUE;
            for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                if (p == mc.player || !p.getGameProfile().name().equalsIgnoreCase(a[0])) continue;
                double d = p.distanceTo(mc.player);
                if (d < best) { best = d; target = p; }
            }
            if (target == null) { ChatUtil.error("No loaded player called " + a[0] + " (they must be within render distance)"); return; }
            Prism.modules().get(dev.prismglass.module.movement.ClickTP.class).teleport(target.position());
            ChatUtil.good("Teleported to \u00a7f" + target.getGameProfile().name() + "\u00a77 (" + Math.round(best) + "m)");
        }, "tpto"));
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
            if (a.length == 0) { mc.schedule(() -> mc.gui.setScreen(new dev.prismglass.gui.AccountsScreen(null))); return; }
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
            if (a.length == 0) { mc.schedule(() -> mc.gui.setScreen(new dev.prismglass.gui.ProxiesScreen(null))); return; }
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
        add(new Command("macro", "macro add <name> <line> | set <name> <line; line...> | del <name> [line#] | bind <name> <key> | run <name> | show <name> | list",
            "Macros: lines run one per tick (.command, /server command, chat, !wait <ticks>). Binds also in ClickGUI > Keybinds.", a -> {
            var mm = Prism.macros();
            String sub = a.length == 0 ? "list" : a[0].toLowerCase(java.util.Locale.ROOT);
            String rest = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "";
            switch (sub) {
                case "add" -> {
                    if (rest.isBlank()) throw new IllegalArgumentException("Missing line");
                    var m = mm.getOrCreate(arg(a, 1));
                    m.lines.add(rest);
                    mm.save();
                    ChatUtil.good("Macro " + m.name + ": line " + m.lines.size() + " added");
                }
                case "set" -> {
                    if (rest.isBlank()) throw new IllegalArgumentException("Missing lines (split them with ;)");
                    var m = mm.getOrCreate(arg(a, 1));
                    m.lines.clear();
                    for (String l : rest.split(";")) if (!l.isBlank()) m.lines.add(l.trim());
                    mm.save();
                    ChatUtil.good("Macro " + m.name + ": " + m.lines.size() + " line(s)");
                }
                case "del", "remove" -> {
                    var m = mm.get(arg(a, 1));
                    if (m == null) throw new IllegalArgumentException("No macro " + a[1]);
                    if (a.length > 2) {
                        int n = Integer.parseInt(a[2]);
                        if (n < 1 || n > m.lines.size()) throw new IllegalArgumentException("Line 1-" + m.lines.size());
                        m.lines.remove(n - 1);
                        ChatUtil.good("Macro " + m.name + ": removed line " + n);
                    } else {
                        mm.remove(m.name);
                        ChatUtil.good("Removed macro " + m.name);
                    }
                    mm.save();
                }
                case "bind" -> {
                    var m = mm.get(arg(a, 1));
                    if (m == null) throw new IllegalArgumentException("No macro " + a[1]);
                    int key = KeyUtil.fromName(arg(a, 2));
                    if (key == Integer.MIN_VALUE) throw new IllegalArgumentException("Unknown key " + a[2]);
                    m.key.set(key);
                    mm.save();
                    ChatUtil.good("Bound macro " + m.name + " to " + KeyUtil.name(key));
                }
                case "run" -> { if (!mm.run(arg(a, 1))) throw new IllegalArgumentException("No macro " + a[1] + " (or it's empty)"); }
                case "stop" -> { mm.stop(); ChatUtil.info("Macro stopped"); }
                case "show" -> {
                    var m = mm.get(arg(a, 1));
                    if (m == null) throw new IllegalArgumentException("No macro " + a[1]);
                    ChatUtil.info("§f" + m.name + " §7[" + KeyUtil.name(m.key.get()) + "]");
                    for (int i = 0; i < m.lines.size(); i++) ChatUtil.info("  §8" + (i + 1) + ". §f" + m.lines.get(i));
                }
                case "list" -> mm.list();
                default -> throw new IllegalArgumentException("Unknown action " + a[0]);
            }
        }, "macros", "m"));
        add(new Command("reconnect", "reconnect", "Rejoin the last server you connected to.", a -> {
            if (!dev.prismglass.module.client.AutoReconnect.reconnect()) throw new IllegalArgumentException("No server joined yet this session");
        }, "rc"));
        add(new Command("addons", "addons", "List loaded Prism addons.", a -> {
            var list = dev.prismglass.api.Addons.loaded();
            if (list.isEmpty()) { ChatUtil.info("No addons. Mods with a \"prismglass\" entrypoint show up here."); return; }
            for (var ad : list) ChatUtil.info((ad.ok() ? "§a" : "§c") + ad.name() + " §7" + ad.version() + " §8(" + ad.modId() + ")" + (ad.ok() ? "" : " §c" + ad.error()));
        }, "addon"));
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

    private static List<String> warmStructureNames(List<String> options) {
        dev.prismglass.xray.StructurePredictor.structureIds();
        return options;
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
            case "crack" -> i == 0 ? List.of("reset", "retry") : List.of();
            // typing .sf starts building the structure/biome names in the background, so they're there by "find "
            case "structurefinder" -> i == 0 ? warmStructureNames(List.of("find", "stop")) : i == 1 && a[0].equalsIgnoreCase("find")
                ? dev.prismglass.module.world.StructureFinder.findNames() : List.of();
            case "plugins" -> i == 0 ? List.of("probe", "deep", "last", "why", "reload") : i == 1 && a[0].equalsIgnoreCase("why") ? Prism.modules().get(dev.prismglass.module.world.PluginScanner.class).result().plugins.stream().map(f -> f.name()).toList() : List.of();
            case "leaveplayer" -> { if (i != 0) yield List.of(); List<String> l = new java.util.ArrayList<>(Prism.bots().names()); l.add("all"); yield l; }
            case "sayplayer" -> { if (i != 0) yield List.of("/"); List<String> l = new java.util.ArrayList<>(Prism.bots().names()); l.add("all"); yield l; }
            case "fakeleave" -> i == 0 ? Prism.fakePlayers().names() : List.of();
            case "switchplayer" -> i == 0 ? Prism.bots().names() : List.of("drop", "fast");
            case "playerfollow" -> {
                if (i == 0) { List<String> l = new java.util.ArrayList<>(Prism.bots().names()); l.add("all"); yield l; }
                if (i == 1) { List<String> l = new java.util.ArrayList<>(playerNames()); l.add("off"); yield l; }
                yield List.of();
            }
            case "playerdrop" -> {
                if (i == 0) { List<String> l = new java.util.ArrayList<>(Prism.bots().names()); l.add("all"); yield l; }
                if (i == 1) yield List.of("all", "1", "2", "3", "4", "5", "6", "7", "8", "9");
                if (i == 2) yield List.of("max", "1", "16", "64");
                yield List.of();
            }
            case "viewplayerbotinventory" -> i == 0 ? Prism.bots().names() : List.of();
            case "goto" -> i == 0 ? playerNames() : List.of();
            case "joinplayer" -> i == 1 ? List.of("true", "false") : List.of();
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
            case "spamdupe" -> i == 0 ? List.of("stop", "5", "10", "50") : List.of();
            case "dupe" -> {
                if (i > 0) yield List.of();
                List<String> l = new java.util.ArrayList<>(dev.prismglass.module.anarchy.PluginDupe.PRESETS.keySet());
                l.add("list"); l.add("stop");
                yield l;
            }
            case "follow" -> { if (i > 0) yield List.of(); List<String> l = new java.util.ArrayList<>(playerNames()); l.add("off"); yield l; }
            case "ai" -> {
                var known = Prism.modules().get(dev.prismglass.module.service.DamageAILearn.class).known();
                if (i == 0) { List<String> l = new java.util.ArrayList<>(known); l.add("list"); l.add("forget"); yield l; }
                yield i == 1 && a[0].equalsIgnoreCase("forget") ? known : List.of();
            }
            case "marker" -> {
                var mk = Prism.modules().get(dev.prismglass.module.render.Markers.class);
                if (i == 0) yield List.of("add", "del", "list", "clear");
                if (i == 1 && (a[0].equalsIgnoreCase("del") || a[0].equalsIgnoreCase("remove"))) yield mk.markers().stream().map(m -> m.name()).toList();
                if (i == 2 && a[0].equalsIgnoreCase("add")) yield List.of("circle", "sphere", "cuboid");
                yield List.of();
            }
            case "macro" -> {
                if (i == 0) yield List.of("add", "set", "del", "remove", "bind", "run", "stop", "show", "list");
                if (i == 1 && !a[0].equalsIgnoreCase("list") && !a[0].equalsIgnoreCase("stop")) yield Prism.macros().all().stream().map(m -> m.name).toList();
                if (i == 2 && a[0].equalsIgnoreCase("bind")) yield KEYS;
                yield List.of();
            }
            default -> {
                var f = completers.get(cmd);
                if (f == null) yield List.of();
                try { yield f.apply(a); } catch (RuntimeException e) { yield List.of(); }
            }
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
