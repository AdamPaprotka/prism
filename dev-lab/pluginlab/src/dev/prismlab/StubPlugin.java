package dev.prismlab;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lab stub: a plugin that only registers the commands its plugin.yml lists and answers them with its name and a usage
 * line, the way the real ones do when run without arguments. One jar per stub, each with its own plugin.yml
 * (LuckPerms, WorldEdit, CoreProtect). It exists to give PluginScanner something to find on a real Paper server.
 */
public final class StubPlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(getName() + " v" + getPluginMeta().getVersion() + " (lab stub) - usage: /" + label + " help");
        return true;
    }
}
