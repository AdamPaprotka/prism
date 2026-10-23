package dev.prismlab;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lab plugin that hides commands the way plugin-hiding plugins do. Switch it from the console / RCON: {@code hidermode N}.
 * <ul>
 *   <li>0 open: nothing is touched;</li>
 *   <li>1 hidden: the tree sent on join is cut down to a whitelist, tab-complete of a bare "/" is empty,
 *       /plugins and /pl are answered like unknown commands (the usual setup, with the usual gap: /version and /help
 *       still complete with plugin names);</li>
 *   <li>2 strict: as 1, but every tab-complete outside the whitelist is empty and /version, /ver, /about are disguised
 *       too. The plugins' own commands still run, so only a command probe can tell they exist;</li>
 *   <li>3 namespaces hidden: only the "plugin:command" copies are removed from the tree (what send-namespaced: false
 *       does), tab-complete is untouched.</li>
 * </ul>
 */
public final class HiderLab extends JavaPlugin implements Listener {
    private static final Set<String> WHITELIST = Set.of("help", "msg", "tell", "w", "me", "trigger", "teammsg", "tm", "list");
    /** Commands whose tab-complete still works in strict mode (player names), nothing that lists plugins. */
    private static final Set<String> TAB_OK = Set.of("msg", "tell", "w", "me");
    private static final Set<String> INFO_1 = Set.of("plugins", "pl", "bukkit:plugins", "bukkit:pl");
    private static final Set<String> INFO_2 = Set.of("plugins", "pl", "bukkit:plugins", "bukkit:pl", "version", "ver", "about", "icanhasbukkit",
        "bukkit:version", "bukkit:ver", "bukkit:about", "?");
    private static volatile int mode;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) return false;
        try {
            mode = Math.max(0, Math.min(3, Integer.parseInt(args[0])));
        } catch (NumberFormatException e) {
            return false;
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands(); // re-send the tree through the filter below
        sender.sendMessage("hider mode " + mode);
        return true;
    }

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent e) {
        int m = mode;
        if (m == 0) return;
        if (m == 3) e.getCommands().removeIf(c -> c.indexOf(':') >= 0);
        else e.getCommands().retainAll(WHITELIST);
    }

    @EventHandler
    public void onTab(AsyncTabCompleteEvent e) {
        int m = mode;
        if (m == 0 || m == 3 || !e.isCommand()) return;
        String buf = e.getBuffer();
        boolean block = m == 1 ? buf.equals("/") : !TAB_OK.contains(head(buf));
        if (block) {
            e.setCompletions(List.of());
            e.setHandled(true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        int m = mode;
        if (m == 0 || m == 3) return;
        if ((m == 1 ? INFO_1 : INFO_2).contains(head(e.getMessage()))) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("Unknown command. Type \"/help\" for help."));
        }
    }

    /** First word of a typed command, without the slash, lowercase. */
    private static String head(String buffer) {
        String s = buffer.startsWith("/") ? buffer.substring(1) : buffer;
        int sp = s.indexOf(' ');
        return (sp < 0 ? s : s.substring(0, sp)).toLowerCase(Locale.ROOT);
    }
}
