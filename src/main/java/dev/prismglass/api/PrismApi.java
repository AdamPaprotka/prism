package dev.prismglass.api;

import dev.prismglass.Prism;
import dev.prismglass.command.CommandManager;
import dev.prismglass.manager.FriendManager;
import dev.prismglass.manager.Macros;
import dev.prismglass.module.Module;
import dev.prismglass.module.ModuleManager;
import java.util.List;
import java.util.function.Function;

/**
 * What an addon can reach. Modules extend {@link Module} (declare settings with its bool/num/mode/... helpers and
 * override the hooks you need: onTick, onRender2D, onRender3D, onPacketSend/Receive, ...) and pick an existing
 * {@link dev.prismglass.module.Category}.
 */
public final class PrismApi {
    private final String addon;

    PrismApi(String addon) { this.addon = addon; }

    /** The addon this handle belongs to. */
    public String addon() { return addon; }

    /** Adds modules to the ClickGUI, commands, binds and profiles. A name Prism already uses is refused. */
    public void registerModule(Module... modules) {
        for (Module m : modules) {
            if (Prism.modules().get(m.getName()) != null)
                throw new IllegalArgumentException("Prism already has a module named " + m.getName());
            Prism.modules().register(m);
        }
    }

    /**
     * Adds a chat command ({@code .name args}). Throw from the handler to show its message plus the usage line.
     * @param completer tab completion for the argument being typed (gets the finished args), or null
     */
    public void registerCommand(String name, String usage, String description, CommandHandler handler,
                                Function<String[], List<String>> completer, String... aliases) {
        Prism.commands().register(name, usage, description + " §8(" + addon + ")", handler, completer, aliases);
    }

    public void registerCommand(String name, String usage, String description, CommandHandler handler, String... aliases) {
        registerCommand(name, usage, description, handler, null, aliases);
    }

    public ModuleManager modules() { return Prism.modules(); }
    public CommandManager commands() { return Prism.commands(); }
    public FriendManager friends() { return Prism.friends(); }
    public Macros macros() { return Prism.macros(); }

    /** The addon's own folder for data files: .minecraft/prism/addons/&lt;addon&gt;. */
    public java.nio.file.Path folder() {
        return Prism.config().folder().resolve("addons").resolve(addon.replaceAll("[^A-Za-z0-9_-]", "_"));
    }
}
