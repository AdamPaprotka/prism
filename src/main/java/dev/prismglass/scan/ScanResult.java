package dev.prismglass.scan;

import java.util.List;
import java.util.Locale;

/** What {@link ScanEngine} concluded. */
public final class ScanResult {
    public enum Visibility {
        /** Commands are exposed normally. */
        OPEN("exposed normally"),
        /** Part of the command surface is trimmed: what is listed may be incomplete. */
        FILTERED("filtered"),
        /** The usual ways of listing plugins are blocked or disguised. */
        HIDDEN("hidden"),
        /** No plugin layer at all (vanilla, or a modded server without Bukkit). */
        NONE("no plugin layer"),
        /** Nothing to judge from yet. */
        UNKNOWN("unknown");

        public final String label;
        Visibility(String label) { this.label = label; }
    }

    /** @param score how many hiding signs were found (3 = filtered, 5 = hidden); {@code reasons} says which. */
    public record View(Visibility verdict, int score, List<String> reasons, boolean bukkitLike) {}

    /** What the ping/transaction stream looked like. {@code kind}: none, grim, negative, stream. */
    public record PingProfile(int total, double perSecond, String kind, int min, int max, double stepRatio) {
        public static PingProfile none() { return new PingProfile(0, 0, "none", 0, 0, 0); }
    }

    /** Plugins the server runs (not software, not bundled), best confidence first. */
    public final List<Finding> plugins;
    /** Bundled with the server software (spark on Paper). */
    public final List<Finding> builtins;
    /** Server software and proxies. */
    public final List<Finding> software;
    /** The anticheat subset of {@link #plugins}. */
    public final List<Finding> anticheats;
    public final View view;
    public final PingProfile ping;
    /** Non-vanilla commands without a namespace that no signature explains. */
    public final List<String> customCommands;
    /** Channels the server used that no signature knows. */
    public final List<String> otherChannels;
    public final TreeAnalyzer.Report tree;

    ScanResult(List<Finding> plugins, List<Finding> builtins, List<Finding> software, List<Finding> anticheats, View view,
               PingProfile ping, List<String> customCommands, List<String> otherChannels, TreeAnalyzer.Report tree) {
        this.plugins = plugins;
        this.builtins = builtins;
        this.software = software;
        this.anticheats = anticheats;
        this.view = view;
        this.ping = ping;
        this.customCommands = customCommands;
        this.otherChannels = otherChannels;
        this.tree = tree;
    }

    /** Looks a finding up by signature id, display name or the "x:name" key (case-insensitive). */
    public Finding find(String what) {
        String w = what.toLowerCase(Locale.ROOT);
        for (List<Finding> group : List.of(plugins, builtins, software)) {
            for (Finding f : group) {
                if (f.key.equals(w) || f.key.equals("x:" + w) || f.name().equalsIgnoreCase(w)) return f;
            }
        }
        return null;
    }

    /** Display names of {@link #plugins}. */
    public List<String> pluginNames() {
        return plugins.stream().map(Finding::name).toList();
    }
}
