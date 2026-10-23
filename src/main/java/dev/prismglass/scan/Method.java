package dev.prismglass.scan;

/**
 * One way of noticing a plugin. {@link #family} groups methods that read the same underlying fact (a plugin's
 * namespaced command shows up in the tree AND in tab-complete): inside a family only the strongest evidence counts, so
 * two views of one fact never add up to "confirmed".
 */
public enum Method {
    TREE_NS("command tree: namespaced command", false, "namespace"),
    TAB_NS("tab-complete: namespaced command", true, "namespace"),
    PREFIX_TAB("tab-complete of a \"plugin:\" prefix", true, "namespace"),
    VERSION_TAB("/version tab-complete (plugin names)", true, "names"),
    HELP_TAB("/help tab-complete (help topics)", true, "names"),
    PLUGINS_CMD("/bukkit:plugins answer", true, "names"),
    COMMAND("command names", false, "commands"),
    SHAPE("command tree shape", false, "shape"),
    CHANNEL("plugin-message channel", false, "channel"),
    PING("ping/transaction packets", false, "ping"),
    BRAND("server brand", false, "software"),
    STATUS("server list version", false, "software"),
    PACK("resource pack host", false, "pack"),
    ERROR_PROBE("answer differs from \"unknown command\"", true, "error");

    public final String label;
    /** true = the scanner had to send something to get this. */
    public final boolean active;
    public final String family;

    Method(String label, boolean active, String family) {
        this.label = label;
        this.active = active;
        this.family = family;
    }
}
