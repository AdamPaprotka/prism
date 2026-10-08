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
