package dev.prismglass;

import java.io.InputStream;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** The build stamp baked into the jar by Gradle (genBuildInfo): version, channel, build number and time. */
public final class BuildInfo {
    private static Properties props;

    private BuildInfo() {}

    private static Properties props() {
        if (props != null) return props;
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/assets/prismglass/build.properties")) {
            if (in != null) p.load(in);
        } catch (Exception e) {
            Prism.LOG.error("[buildinfo] read failed", e);
        }
        return props = p;
    }

    public static String get(String key, String fallback) { return props().getProperty(key, fallback); }

    /** release / beta / indev. Running from the dev environment is always indev. */
    public static String channel() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return "indev";
        return get("channel", "indev");
    }

    public static String build() { return get("build", "?"); }

    public static String time() { return get("time", "unknown"); }

    /** e.g. "1.2.0-beta (build 7)". */
    public static String full() { return Prism.VERSION + "-" + channel() + " (build " + build() + ")"; }
}
