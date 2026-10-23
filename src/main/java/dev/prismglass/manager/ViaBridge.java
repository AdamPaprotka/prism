package dev.prismglass.manager;

import dev.prismglass.Prism;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.Connection;

/**
 * ViaFabricPlus glue, by reflection so Prism builds and runs without it. VFP translates a connection only when the
 * {@code Connection} carries a target version (its mixin interface {@code IConnection}); the connect screen sets it,
 * so bots must copy the stamp from your live connection or they would talk native 26.1.2 to an older server.
 */
public final class ViaBridge {
    public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("viafabricplus");
    private static final String GET = "viaFabricPlus$getTargetVersion", SET = "viaFabricPlus$setTargetVersion";

    private ViaBridge() {}

    /** The ProtocolVersion VFP uses for this connection, or null (no VFP / native). */
    public static Object targetVersion(Connection c) {
        if (!PRESENT || c == null) return null;
        try {
            Method m = c.getClass().getMethod(GET);
            return m.invoke(c);
        } catch (Throwable t) {
            Prism.LOG.warn("ViaFabricPlus: could not read the target version", t);
            return null;
        }
    }

    /** Stamp the same version on a new connection before it connects. */
    public static boolean setTargetVersion(Connection c, Object version) {
        if (!PRESENT || c == null || version == null) return false;
        try {
            for (Method m : c.getClass().getMethods()) {
                if (m.getName().equals(SET) && m.getParameterCount() == 1) {
                    m.invoke(c, version);
                    return true;
                }
            }
        } catch (Throwable t) {
            Prism.LOG.warn("ViaFabricPlus: could not set the target version", t);
        }
        return false;
    }

    public static String describe(Object version) { return version == null ? "native" : String.valueOf(version); }
}
