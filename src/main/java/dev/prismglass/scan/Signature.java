package dev.prismglass.scan;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a known plugin (or server software) looks like from the client. Every field except id/name/kind is optional.
 * See {@code assets/prismglass/plugin_signatures.json} for the file format and how to add one.
 *
 * @param ns       namespaces the plugin registers commands under ("luckperms" for "luckperms:lp")
 * @param cmds     distinctive command names (without the leading slash): one hit is already a good sign
 * @param weak     names other plugins use too: only add up to a weak sign
 * @param shapes   sub-command sets that identify the plugin even when its commands were renamed
 * @param channels plugin-message channels: exact ("worldedit:cui") or prefix ("voicechat:", "fabric*")
 * @param brand    regexes tried on the server brand
 * @param status   regexes tried on the server-list version text
 * @param pack     regexes tried on the host of a server resource pack
 * @param ping     "grim" (negative ids stepping by one) or "transaction" (any ping stream), for anticheats
 * @param probe    a command that is harmless without arguments, used by the opt-in error probe
 */
public record Signature(String id, String name, String kind, boolean builtin, boolean bukkit, int priority, String note,
                        List<String> ns, List<String> cmds, List<String> weak, List<Shape> shapes, List<String> channels,
                        List<Pattern> brand, List<Pattern> status, List<Pattern> pack, String ping, String probe) {

    /** At least {@code min} of {@code has} are literal children of one command, whatever that command is called. */
    public record Shape(List<String> has, int min) {}

    public boolean software() { return kind.equals("software") || kind.equals("proxy"); }

    /** True when {@code channel} ("namespace:path") is this signature's. Returns the strength: 2 exact, 1 prefix, 0 none. */
    public int matchChannel(String channel) {
        String c = channel.toLowerCase(Locale.ROOT);
        int best = 0;
        for (String p : channels) {
            if (p.equals(c)) return 2;
            if (p.endsWith("*") && c.startsWith(p.substring(0, p.length() - 1))) best = 1;
            else if (p.endsWith(":") && c.startsWith(p)) best = 1;
        }
        return best;
    }
}
