package dev.prismglass.api;

/**
 * Entrypoint for Prism addons. In your addon's fabric.mod.json:
 * <pre>
 * "entrypoints": { "prismglass": ["com.example.myaddon.MyAddon"] },
 * "depends": { "prismglass": "*" }
 * </pre>
 * Prism calls {@link #onInitialize} once at startup, after its own modules and before the config loads,
 * so settings of the modules you register are saved and loaded with the user's profiles.
 */
public interface PrismAddon {
    /** Register modules, commands and macros here. */
    void onInitialize(PrismApi api);

    /** Shown in .addons and the logs; defaults to the mod's name from fabric.mod.json. */
    default String name() { return null; }
}
