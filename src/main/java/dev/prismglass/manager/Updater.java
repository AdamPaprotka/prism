package dev.prismglass.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.prismglass.Prism;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Updates from the GitHub releases of AdamPaprotka/prism (public, no token).
 *
 * <p>check(): latest release vs our version. download(): the release jar for our Minecraft version goes next to the
 * running jar as "*.jar.tmp" (Fabric only loads *.jar). Windows keeps the running jar locked, and two Prism jars in
 * mods/ would stop Fabric from starting (duplicate mod id), so a detached PowerShell command waits for this game
 * process to exit, deletes the old jar and renames the new one. The next launch is on the new version.
 */
public final class Updater {
    private static final String API = "https://api.github.com/repos/AdamPaprotka/prism/releases/latest";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED, DEV }

    public static volatile State state = State.IDLE;
    public static volatile String latest = "", error = "";
    public static volatile float progress;
    private static volatile String assetUrl, assetName;
    private static volatile long assetSize;

    private Updater() {}

    /** Our own jar, or null in a dev run (classes folder) where there's nothing to replace. */
    private static Path ownJar() {
        return FabricLoader.getInstance().getModContainer("prismglass")
            .flatMap(c -> c.getOrigin().getPaths().stream().findFirst())
            .filter(p -> p.toString().endsWith(".jar")).orElse(null);
    }

    /** "1.4.1" newer than "1.4.0"? Numeric per part; anything unparsable counts as 0. */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.+-]"), y = b.split("[.+-]");
        for (int i = 0; i < 3; i++) {
            int u = i < x.length ? num(x[i]) : 0, v = i < y.length ? num(y[i]) : 0;
            if (u != v) return u > v;
        }
        return false;
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; } }

    /** Asks GitHub for the latest release (async). Toasts once when there's a newer one. */
    public static void check(boolean toast) {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.READY) return;
        state = State.CHECKING;
        HttpRequest req = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(10))
            .header("User-Agent", "PrismGlass/" + Prism.VERSION).header("Accept", "application/vnd.github+json").GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, err) -> {
            try {
                if (err != null || r.statusCode() != 200) throw new IllegalStateException(err != null ? err.toString() : "HTTP " + r.statusCode());
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                String tag = o.get("tag_name").getAsString().replaceFirst("^v", "");
                latest = tag;
                if (!newer(tag, Prism.VERSION)) { state = State.UP_TO_DATE; return; }
                // the jar built for our Minecraft version (same "+mc<version>" suffix as ours)
                Path own = ownJar();
                String suffix = own == null ? null : own.getFileName().toString().replaceAll("^.*(\\+mc[^/]*\\.jar)$", "$1");
                assetUrl = null;
                for (JsonElement e : o.getAsJsonArray("assets")) {
                    JsonObject a = e.getAsJsonObject();
                    String name = a.get("name").getAsString();
                    if (!name.endsWith(".jar")) continue;
                    if (assetUrl == null || suffix != null && name.endsWith(suffix)) {
                        assetUrl = a.get("browser_download_url").getAsString();
                        assetName = name;
                        assetSize = a.get("size").getAsLong();
                    }
                }
                state = assetUrl == null ? State.FAILED : State.AVAILABLE;
                if (assetUrl == null) error = "no jar in release " + tag;
                else if (toast) toast("Prism " + tag + " is out: Prism logo > Update");
            } catch (Exception e) {
                error = e.getMessage();
                state = State.FAILED;
            }
        });
    }

    /** Downloads the new jar next to ours and schedules the swap for when the game closes. */
    public static void download() {
        if (state != State.AVAILABLE) return;
        Path own = ownJar();
        if (own == null) { state = State.DEV; return; }
        state = State.DOWNLOADING;
        progress = 0;
        Thread t = new Thread(() -> {
            Path dir = own.getParent(), tmp = dir.resolve(assetName + ".tmp"), target = dir.resolve(assetName);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(assetUrl)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "PrismGlass/" + Prism.VERSION).GET().build();
                HttpResponse<java.io.InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
                try (var in = r.body(); var out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        if (assetSize > 0) progress = (float) done / assetSize;
                    }
                }
                if (assetSize > 0 && Files.size(tmp) != assetSize) throw new IllegalStateException("download incomplete");
                // after this game exits: old jar out, new jar in (single quotes doubled for PowerShell literals)
                String q1 = own.toString().replace("'", "''"), q2 = tmp.toString().replace("'", "''"), q3 = target.toString().replace("'", "''");
                String cmd = "Wait-Process -Id " + ProcessHandle.current().pid() + " -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500; "
                    + "for ($i = 0; $i -lt 20; $i++) { try { Remove-Item -LiteralPath '" + q1 + "' -Force -ErrorAction Stop; break } catch { Start-Sleep -Milliseconds 500 } }; "
                    + "if (-not (Test-Path -LiteralPath '" + q1 + "')) { Move-Item -LiteralPath '" + q2 + "' -Destination '" + q3 + "' -Force }";
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", cmd).start();
                state = State.READY;
                toast("Prism " + latest + " downloaded: restart Minecraft to finish");
            } catch (Exception e) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
                error = e.getMessage();
                state = State.FAILED;
            }
        }, "Prism-Updater");
        t.setDaemon(true);
        t.start();
    }

    private static void toast(String text) {
        net.minecraft.client.Minecraft.getInstance().execute(() -> dev.prismglass.module.client.Hud.message(text, 0xFF5CFF9D));
    }

    /** Label for an update button. */
    public static String label() {
        return switch (state) {
            case IDLE, FAILED -> "Check updates";
            case CHECKING -> "Checking...";
            case UP_TO_DATE -> "Up to date";
            case AVAILABLE -> "Update to " + latest;
            case DOWNLOADING -> String.format("Downloading %d%%", Math.round(progress * 100));
            case READY -> "Restart to update";
            case DEV -> "Dev build";
        };
    }

    /** What clicking the button does: check, or download when there's an update. */
    public static void click() {
        if (state == State.AVAILABLE) download();
        else if (state == State.IDLE || state == State.FAILED || state == State.UP_TO_DATE) check(false);
    }
}
