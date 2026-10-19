package dev.prismglass.manager;

import dev.prismglass.Prism;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Runs the Tor Expert Bundle's tor.exe as a child process: SOCKS5 on 127.0.0.1:9050, control port 9051 with cookie
 * auth, its own data directory under prism/tor. If something already listens on 9050 (Tor Browser, your own tor
 * service) that one is used instead and nothing is started. "New identity" asks the control port for a fresh circuit.
 */
public final class TorRunner {
    public static final String HOST = "127.0.0.1";
    public static final int SOCKS_PORT = 9050, CONTROL_PORT = 9051;
    private static final Pattern BOOTSTRAP = Pattern.compile("Bootstrapped (\\d+)%");

    private final Path dataDir = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("tor");
    private Process process;
    private volatile int bootstrap;
    private volatile boolean external;
    private volatile String status = "stopped";
    private volatile String lastLine = "";

    /** Where tor.exe may live: next to the game (prism/tor), the user's home, or the Tor Browser install. */
    public static List<Path> candidates() {
        List<Path> c = new ArrayList<>();
        Path game = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("tor");
        c.add(game.resolve("tor.exe"));
        c.add(game.resolve("tor").resolve("tor.exe"));
        String home = System.getProperty("user.home");
        c.add(Path.of(home, "tor", "tor", "tor.exe"));
        c.add(Path.of(home, "tor", "tor.exe"));
        c.add(Path.of(home, "Desktop", "Tor Browser", "Browser", "TorBrowser", "Tor", "tor.exe"));
        String custom = System.getProperty("prismglass.tor");
        if (custom != null) c.add(0, Path.of(custom));
        return c;
    }

    /** Where the install guide tells people to put the bundle: prism/tor in this profile's game folder. */
    public static Path installDir() { return FabricLoader.getInstance().getGameDir().resolve("prism").resolve("tor").toAbsolutePath(); }

    /** Writes prism/torinstall.txt (with this profile's real folder in it) and returns its path. */
    public static Path writeInstallGuide() throws IOException {
        Path dir = installDir();
        Files.createDirectories(dir);
        Path guide = dir.getParent().resolve("torinstall.txt");
        String text = String.join(System.lineSeparator(),
            "PRISM - INSTALLING TOR",
            "",
            "Prism can route your Minecraft connection through Tor so servers see a Tor exit IP instead of yours.",
            "It needs the official tor.exe. Prism does not ship it, you download it from the Tor Project (free, ~20 MB).",
            "",
            "1. Open  https://www.torproject.org/download/tor/",
            "2. Download \"Windows Expert Bundle\" (x86_64). It is a .tar.gz archive.",
            "3. Extract it (7-Zip or Windows Explorer can open .tar.gz) and put the files into this folder:",
            "",
            "   " + dir,
            "",
            "   When done, this file must exist:",
            "   " + dir.resolve("tor").resolve("tor.exe"),
            "   (Prism also accepts " + dir.resolve("tor.exe") + ", or ~\tor\tor\tor.exe in your user folder.)",
            "",
            "4. Back in Minecraft: ClickGUI -> Accounts -> Proxies, press Start on the Tor row. First start takes ~20 s",
            "   (\"bootstrapping\" -> \"Tor connected\"). Press Use, then join a server. \"Check IP\" shows what the server sees.",
            "",
            "Notes",
            "- If Tor Browser is already running, Prism uses its Tor on port 9050 and you don't need the bundle at all.",
            "- Expect 250-500 ms ping on Tor: fine for chat, scouting and plugin scans, not for fighting.",
            "- Some servers block Tor exits; \"New identity\" gets a fresh exit, usually one that works.",
            "- Your real IP is still used for the Mojang session check of a premium account and for DNS; cracked accounts",
            "  (Accounts screen) have no session check at all.",
            "");
        Files.writeString(guide, text);
        return guide;
    }

    /** Writes the guide and opens it in the default text editor. */
    public static boolean openInstallGuide() {
        try {
            net.minecraft.util.Util.getPlatform().openPath(writeInstallGuide());
            return true;
        } catch (Exception e) {
            Prism.LOG.warn("Could not open torinstall.txt", e);
            return false;
        }
    }

    public static Path findTor() {
        for (Path p : candidates()) if (Files.isRegularFile(p)) return p;
        return null;
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean running() { return external || (process != null && process.isAlive()); }
    public boolean ready() { return external || (running() && bootstrap >= 100); }
    public int bootstrap() { return external ? 100 : bootstrap; }
    public String status() { return status; }

    /** Start tor (or adopt one already listening on 9050). Returns false with {@link #status()} explaining why. */
    public synchronized boolean start() {
        if (running()) return true;
        if (portOpen(SOCKS_PORT)) {
            external = true;
            status = "using the Tor already on " + HOST + ":" + SOCKS_PORT;
            return true;
        }
        Path exe = findTor();
        if (exe == null) {
            status = "tor.exe not found (put the Tor Expert Bundle in prism/tor or ~/tor)";
            return false;
        }
        try {
            Files.createDirectories(dataDir);
            ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString(),
                "--SocksPort", HOST + ":" + SOCKS_PORT,
                "--ControlPort", HOST + ":" + CONTROL_PORT,
                "--CookieAuthentication", "1",
                "--CookieAuthFile", dataDir.resolve("control_auth_cookie").toString(),
                "--DataDirectory", dataDir.toString(),
                "--ClientOnly", "1",
                "--Log", "notice stdout");
            pb.redirectErrorStream(true);
            pb.directory(exe.toAbsolutePath().getParent().toFile());
            process = pb.start();
            bootstrap = 0;
            status = "starting";
            Thread t = new Thread(this::pump, "prism-tor");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException e) {
            status = "could not start tor: " + e.getMessage();
            Prism.LOG.warn("tor start failed", e);
            return false;
        }
    }

    private void pump() {
        Process p = process;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastLine = line;
                Matcher m = BOOTSTRAP.matcher(line);
                if (m.find()) {
                    bootstrap = Integer.parseInt(m.group(1));
                    status = bootstrap >= 100 ? "connected" : "bootstrapping " + bootstrap + "%";
                } else if (line.contains("[warn]") || line.contains("[err]")) {
                    status = line.replaceAll("^.*?\\[(warn|err)] ", "").trim();
                }
            }
        } catch (IOException ignored) {}
        if (process == p) {
            status = bootstrap >= 100 ? "stopped" : "exited: " + lastLine.replaceAll("^.*?] ", "");
            bootstrap = 0;
        }
    }

    public synchronized void stop() {
        external = false;
        if (process == null) return;
        Process p = process;
        process = null;
        p.destroy();
        try { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) {}
        bootstrap = 0;
        status = "stopped";
    }

    /** SIGNAL NEWNYM over the control port: next connections use a new circuit (and usually a new exit IP). */
    public boolean newIdentity() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, CONTROL_PORT), 1000);
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            Path cookie = dataDir.resolve("control_auth_cookie");
            String auth = Files.exists(cookie) ? "AUTHENTICATE " + HexFormat.of().formatHex(Files.readAllBytes(cookie)) : "AUTHENTICATE \"\"";
            out.write((auth + "\r\nSIGNAL NEWNYM\r\nQUIT\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String a = in.readLine(), b = in.readLine();
            boolean ok = a != null && a.startsWith("250") && b != null && b.startsWith("250");
            if (!ok) status = "new identity refused: " + (a == null ? "no answer" : a);
            return ok;
        } catch (IOException e) {
            status = "control port: " + e.getMessage();
            return false;
        }
    }
}
