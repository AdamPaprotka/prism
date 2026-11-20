package dev.prismglass.api;

import dev.prismglass.Prism;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** Finds and starts every mod with a "prismglass" entrypoint. */
public final class Addons {
    public record Info(String name, String modId, String version, boolean ok, String error) {}

    private static final List<Info> LOADED = new ArrayList<>();

    private Addons() {}

    public static List<Info> loaded() { return Collections.unmodifiableList(LOADED); }

    public static void loadAll() {
        for (var c : FabricLoader.getInstance().getEntrypointContainers("prismglass", PrismAddon.class)) {
            var meta = c.getProvider().getMetadata();
            String name = meta.getName();
            try {
                PrismAddon addon = c.getEntrypoint();
                if (addon.name() != null && !addon.name().isBlank()) name = addon.name();
                load(addon, name, meta.getId(), meta.getVersion().getFriendlyString());
            } catch (Throwable t) {
                Prism.LOG.error("Prism addon {} failed to load", name, t);
                LOADED.add(new Info(name, meta.getId(), meta.getVersion().getFriendlyString(), false, String.valueOf(t)));
            }
        }
    }

    /** Also used by the dev addon test to load an addon without a separate mod. */
    public static void load(PrismAddon addon, String name, String modId, String version) {
        try {
            addon.onInitialize(new PrismApi(name));
            LOADED.add(new Info(name, modId, version, true, null));
            Prism.LOG.info("Loaded Prism addon {} {} ({})", name, version, modId);
        } catch (Throwable t) {
            Prism.LOG.error("Prism addon {} failed in onInitialize", name, t);
            LOADED.add(new Info(name, modId, version, false, String.valueOf(t)));
        }
    }
}
