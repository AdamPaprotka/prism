package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.client.Hud;
import dev.prismglass.module.world.PluginScanner;
import dev.prismglass.scan.Evidence;
import dev.prismglass.scan.Finding;
import dev.prismglass.scan.ScanResult;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Dev only (-Ppluginstest). Two modes, picked by whether -Pmp=host:port was given:
 * <ul>
 *   <li>local (no -Pmp): {@link PluginsLocalTest}, the integrated server dressed up as a Bukkit one;</li>
 *   <li>network (-Pmp=localhost:25610 -Prconport=25611): a real Paper server running the stub plugins from
 *       dev-lab/pluginlab, switched between "open", "hidden" and "strict" by RCON.</li>
 * </ul>
 */
public final class PluginsTest {
    static final Minecraft mc = Minecraft.getInstance();
    static int passed, failed;

    private PluginsTest() {}

    public static void install() {
        boolean net = false;
        for (String a : FabricLoader.getInstance().getLaunchArguments(true)) if (a.equals("--quickPlayMultiplayer")) net = true;
        if (net) PluginsNetTest.install(); else PluginsLocalTest.install();
    }

    static void log(String s) { Prism.LOG.info("[pluginstest] {}", s); }

    static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    static void finish() {
        log("passed " + passed + " failed " + failed);
        log(failed == 0 ? "ALL PASS" : "SOME FAILED");
        Runtime.getRuntime().halt(0);
    }

    /** One line per finding: name, confidence, the methods that found it. No chat text is ever involved. */
    static void dump(ScanResult r) {
        log("verdict " + r.view.verdict() + " (score " + r.view.score() + ", bukkitLike " + r.view.bukkitLike() + ")");
        for (String reason : r.view.reasons()) log("  reason: " + reason);
        for (Finding f : r.software) log("  software " + f.name() + " " + f.confidence() + " " + f.methods());
        for (Finding f : r.plugins) {
            log("  plugin " + f.name() + " " + f.confidence() + " " + f.score() + "% " + f.methods());
            for (Evidence e : f.evidence()) log("      " + e.method() + " " + e.weight() + ": " + e.detail());
        }
        for (Finding f : r.builtins) log("  builtin " + f.name() + " " + f.confidence() + " " + f.methods());
        log("  custom commands " + r.customCommands);
        log("  other channels " + r.otherChannels);
        log("  ping " + r.ping);
    }

    /** Minimal Source RCON client: authenticate, run one command, return the answer. */
    static String rcon(int port, String password, String command) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, 1, 3, password);
            read(in); // the auth answer (Minecraft sends exactly one packet for it)
            send(out, 2, 2, command);
            return read(in);
        } catch (IOException e) {
            return "rcon error: " + e.getMessage();
        }
    }

    private static void send(DataOutputStream out, int id, int type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(buf.array());
        out.flush();
    }

    private static String read(DataInputStream in) throws IOException {
        byte[] head = in.readNBytes(4);
        int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] body = in.readNBytes(len);
        return new String(body, 8, Math.max(0, len - 10), StandardCharsets.UTF_8);
    }

    static boolean hudHasPanel() { return Prism.modules().get(Hud.class).bounds().containsKey("Plugins"); }
}
