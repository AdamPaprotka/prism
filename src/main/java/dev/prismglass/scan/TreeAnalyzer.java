package dev.prismglass.scan;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import java.util.*;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.data.registries.VanillaRegistries;

/**
 * Reads the command tree the server sent (the raw {@code ClientboundCommandsPacket}, as built by the client) and
 * boils it down to what fingerprints a server: which root commands exist, which are namespaced, which look like Bukkit
 * commands (a literal with one greedy "args" string the server completes) and which sub-commands a real Brigadier
 * command offers.
 */
public final class TreeAnalyzer {
    /** Commands every Bukkit-based server has (or had) that are not a plugin's. */
    private static final Set<String> BUKKIT_BUILTIN = Set.of("plugins", "pl", "version", "ver", "about", "reload", "rl", "help", "?",
        "timings", "icanhasbukkit", "bukkit", "paper", "spigot", "mspt", "tps", "callback", "restart", "stop", "whitelist", "ban", "ban-ip",
        "banlist", "pardon", "pardon-ip", "kick", "op", "deop", "list", "me", "msg", "tell", "w", "say", "teammsg", "tm", "trigger", "tellraw");

    /** Fallback when the vanilla dispatcher cannot be built (26.1.2 root commands). */
    private static final List<String> VANILLA_FALLBACK = List.of("advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear",
        "clone", "damage", "data", "datapack", "debug", "defaultgamemode", "deop", "dialog", "difficulty", "effect", "enchant", "execute",
        "experience", "xp", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule", "give", "help", "item", "jfr", "kick",
        "kill", "list", "locate", "loot", "me", "msg", "tell", "w", "op", "pardon", "pardon-ip", "particle", "perf", "place", "playsound",
        "publish", "random", "recipe", "reload", "return", "ride", "rotate", "save-all", "save-off", "save-on", "say", "schedule",
        "scoreboard", "seed", "setblock", "setidletimeout", "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop", "stopsound",
        "summon", "tag", "team", "teammsg", "tm", "teleport", "tp", "tellraw", "tick", "time", "title", "transfer", "trigger", "version",
        "weather", "whitelist", "worldborder");

    private static volatile Set<String> vanilla;

    /** A root command of the tree. {@code literals} are its direct literal sub-commands, lowercase. */
    public record Root(String name, boolean executable, List<String> literals, boolean bukkitStyle, String args) {}

    public record Report(List<Root> roots, int nodes) {
        public static Report empty() { return new Report(List.of(), 0); }

        /** Namespace -> its command names, for every "ns:cmd" root. */
        public Map<String, Set<String>> namespaced() {
            Map<String, Set<String>> out = new TreeMap<>();
            for (Root r : roots) {
                int c = r.name().indexOf(':');
                if (c <= 0 || c == r.name().length() - 1) continue;
                out.computeIfAbsent(r.name().substring(0, c).toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(r.name().substring(c + 1));
            }
            return out;
        }

        /** Root names without a namespace, lowercase. */
        public Set<String> plain() {
            Set<String> out = new TreeSet<>();
            for (Root r : roots) if (r.name().indexOf(':') < 0) out.add(r.name().toLowerCase(Locale.ROOT));
            return out;
        }

        public Set<String> all() {
            Set<String> out = new TreeSet<>();
            for (Root r : roots) out.add(r.name().toLowerCase(Locale.ROOT));
            return out;
        }

        public int bukkitStyleCount() {
            int n = 0;
            for (Root r : roots) if (r.bukkitStyle()) n++;
            return n;
        }

        public boolean isEmpty() { return roots.isEmpty(); }
    }

    private TreeAnalyzer() {}

    public static Report analyze(CommandNode<?> root) {
        if (root == null) return Report.empty();
        List<Root> out = new ArrayList<>();
        int nodes = 1;
        for (CommandNode<?> n : root.getChildren()) {
            nodes += 1 + n.getChildren().size();
            List<String> literals = new ArrayList<>();
            boolean bukkit = false;
            StringBuilder args = new StringBuilder();
            for (CommandNode<?> c : n.getChildren()) {
                if (c instanceof ArgumentCommandNode<?, ?> a) {
                    if (args.length() < 80) args.append(args.length() == 0 ? "" : ",").append(a.getName()).append(':').append(a.getType().getClass().getSimpleName().replace("ArgumentType", ""));
                    if (a.getName().equals("args") && a.getType() instanceof StringArgumentType s && s.getType() == StringArgumentType.StringType.GREEDY_PHRASE
                        && n.getChildren().size() == 1 && askServer(a)) bukkit = true;
                } else if (literals.size() < 96) {
                    literals.add(c.getName().toLowerCase(Locale.ROOT));
                }
            }
            out.add(new Root(n.getName(), n.getCommand() != null, List.copyOf(literals), bukkit, args.toString()));
        }
        return new Report(List.copyOf(out), nodes);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean askServer(ArgumentCommandNode<?, ?> a) {
        try {
            var provider = a.getCustomSuggestions();
            return provider != null && SuggestionProviders.getName((com.mojang.brigadier.suggestion.SuggestionProvider) provider).getPath().equals("ask_server");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Root command names of this exact Minecraft version, built from the game's own command registration. */
    public static Set<String> vanillaCommands() {
        Set<String> v = vanilla;
        if (v != null) return v;
        Set<String> out = new HashSet<>();
        try {
            CommandBuildContext ctx = Commands.createValidationContext(VanillaRegistries.createWorldLookup());
            for (CommandNode<?> n : new Commands(Commands.CommandSelection.ALL, ctx).getDispatcher().getRoot().getChildren()) out.add(n.getName());
        } catch (Throwable t) {
            out.addAll(VANILLA_FALLBACK);
        }
        return vanilla = Set.copyOf(out);
    }

    /** True for a vanilla command or one every Bukkit server ships (plain name, lowercase). */
    public static boolean isStandard(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return vanillaCommands().contains(n) || BUKKIT_BUILTIN.contains(n);
    }

    /** The default-visible Bukkit info commands: when a Bukkit-based server lacks all of these, someone removed them. */
    public static final List<String> BUKKIT_INFO = List.of("plugins", "pl", "version", "ver", "about", "?");
}
