package dev.prismglass.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.prismglass.Prism;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Now playing from the Spotify desktop app, through the Windows media session (the same thing the volume flyout
 * shows), so there's no login: a PowerShell script (assets/prismglass/spotify.ps1, no helper exe for antivirus to
 * flag) prints the track, timeline and Spotify's own cover. Other players' covers come from an iTunes search. Synced lyrics come from Musixmatch when the source is Spotify (its desktop app's API, with
 * one guest token reused and saved in prism/musixmatch.properties: it bans IPs that hammer it), else or on a miss
 * from LRCLIB; the cover comes from the iTunes search API. Only the artist, title and album are sent. Nothing is logged.
 */
public final class NowPlaying {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    public static final Identifier COVER = Identifier.fromNamespaceAndPath("prismglass", "dynamic/now_playing_cover");

    public record Line(long time, String text) {}

    private static Process process;
    private static boolean anyPlayerRunning;
    private static volatile boolean ok;
    public static volatile String title = "", artist = "", album = "";
    private static volatile long duration, posAt, posTime;
    private static volatile boolean playing, fromSpotify;
    /** Synced lyric lines (empty: none found / still loading). */
    public static volatile List<Line> lyrics = List.of();
    public static volatile boolean lyricsLoading;
    private static volatile boolean hasCover, coverFromSession;
    public static volatile int coverW = 96, coverH = 96;
    private static String trackKey = "";
    private static volatile int generation;

    private NowPlaying() {}

    /** Keep the helper running while wanted (Windows only). Call every frame from the HUD. */
    public static void keep(boolean wanted, boolean anyPlayer) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        boolean alive = process != null && process.isAlive();
        if (wanted && alive && anyPlayer != anyPlayerRunning) { stop(); alive = false; }
        if (wanted && !alive) start(anyPlayer);
        else if (!wanted && alive) stop();
    }

    public static boolean active() { return ok && !title.isEmpty(); }
    public static boolean playing() { return playing; }
    public static boolean hasCover() { return hasCover; }
    public static long duration() { return duration; }

    /** Current playback position in ms, extrapolated between helper updates. */
    public static long position() {
        long p = posAt + (playing ? System.currentTimeMillis() - posTime : 0);
        return duration > 0 ? Math.min(p, duration) : p;
    }

    /** Index of the lyric line being sung (-1 before the first). */
    public static int lineAt(long pos) {
        List<Line> l = lyrics;
        int lo = 0, hi = l.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).time() <= pos) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return best;
    }

    private static void start(boolean anyPlayer) {
        try {
            Path dir = mc.gameDirectory.toPath().resolve("prism");
            Files.createDirectories(dir);
            // an earlier 1.4.0 test build compiled a helper exe here; antivirus hates unknown exes, so it's gone
            Files.deleteIfExists(dir.resolve("nowplaying.exe"));
            Files.deleteIfExists(dir.resolve("nowplaying.cs"));
            Path script = dir.resolve("spotify.ps1");
            try (InputStream in = NowPlaying.class.getResourceAsStream("/assets/prismglass/spotify.ps1")) {
                if (in == null) return;
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-AnyPlayer", anyPlayer ? "1" : "0", "-ParentPid", String.valueOf(ProcessHandle.current().pid()));
            pb.redirectErrorStream(true);
            process = pb.start();
            anyPlayerRunning = anyPlayer;
            Process p = process;
            Thread t = new Thread(() -> read(p), "Prism-NowPlaying");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            Prism.LOG.warn("Now playing helper failed to start: {}", e.toString());
            process = null;
        }
    }

    public static void stop() {
        if (process != null) process.destroy();
        process = null;
        ok = false;
    }

    private static void read(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("{")) continue;
                JsonObject o;
                try { o = JsonParser.parseString(line).getAsJsonObject(); } catch (Exception e) { continue; }
                update(o);
            }
        } catch (Exception ignored) {
        }
        if (process == p) ok = false;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static void update(JsonObject o) {
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) { ok = false; return; }
        String t = str(o, "title"), a = str(o, "artist"), al = str(o, "album");
        long pos = o.has("pos") ? o.get("pos").getAsLong() : 0;
        long dur = o.has("dur") ? o.get("dur").getAsLong() : 0;
        boolean pl = o.has("playing") && o.get("playing").getAsBoolean();
        fromSpotify = o.has("spotify") && o.get("spotify").getAsBoolean();
        String key = a + "|" + t;
        if (!key.equals(trackKey)) {
            trackKey = key;
            title = t; artist = a; album = al; duration = dur;
            posAt = pos; posTime = System.currentTimeMillis();
            lyrics = List.of();
            hasCover = false;
            coverFromSession = false;
            int gen = ++generation;
            if (!t.isEmpty()) {
                fetchLyrics(gen, t, a, al, dur);
                fetchCover(gen, t, a);
            }
        } else {
            duration = dur;
            // only resync on a real jump (seek / pause) so the scroll doesn't jitter on helper latency
            long predicted = posAt + (playing ? System.currentTimeMillis() - posTime : 0);
            if (pl != playing || Math.abs(predicted - pos) > 700) { posAt = pos; posTime = System.currentTimeMillis(); }
            else if (!pl) { posAt = pos; posTime = System.currentTimeMillis(); }
        }
        playing = pl;
        ok = true;
        String art = str(o, "art");
        if (!art.isEmpty()) sessionCover(generation, art);
    }

    /** Spotify's own cover (from the media session); wins over the iTunes guess. */
    private static void sessionCover(int gen, String b64) {
        byte[] bytes;
        try { bytes = java.util.Base64.getDecoder().decode(b64); } catch (Exception e) { return; }
        mc.execute(() -> {
            if (gen != generation) return;
            try {
                NativeImage img = NativeImage.read(bytes); // Spotify sends PNG; anything else falls back to iTunes
                coverW = img.getWidth();
                coverH = img.getHeight();
                mc.getTextureManager().register(COVER, new DynamicTexture(() -> "prism now playing cover", img));
                hasCover = true;
                coverFromSession = true;
            } catch (Exception ignored) {
            }
        });
    }

    // ---- lyrics (LRCLIB) ----------------------------------------------------------------------------

    private static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]\\s*(.*)");

    private static String q(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static void fetchLyrics(int gen, String t, String a, String al, long dur) {
        lyricsLoading = true;
        if (fromSpotify && !mxmDead) {
            // Musixmatch first (what Spotify itself shows); one lookup per title candidate, off-thread, one at a time
            MXM.execute(() -> {
                List<Line> l = List.of();
                for (String cand : titleCandidates(t)) {
                    if (gen != generation || mxmDead) break;
                    l = musixmatch(cand, a, true);
                    if (!l.isEmpty()) break;
                }
                if (gen != generation) return;
                if (!l.isEmpty()) { lyrics = l; lyricsLoading = false; }
                else lrclib(gen, t, a, al, dur);
            });
            return;
        }
        lrclib(gen, t, a, al, dur);
    }

    private static void lrclib(int gen, String t, String a, String al, long dur) {
        String url = "https://lrclib.net/api/get?track_name=" + q(t) + "&artist_name=" + q(a)
            + (al.isEmpty() ? "" : "&album_name=" + q(al)) + (dur > 0 ? "&duration=" + Math.round(dur / 1000.0) : "");
        get(url).thenCompose(body -> {
            List<Line> l = body == null ? List.of() : parse(JsonParser.parseString(body).getAsJsonObject());
            if (!l.isEmpty()) return java.util.concurrent.CompletableFuture.completedFuture(l);
            // looser search: first result that has synced lyrics
            return get("https://lrclib.net/api/search?track_name=" + q(t) + "&artist_name=" + q(a)).thenApply(b -> {
                if (b == null) return List.<Line>of();
                JsonArray arr = JsonParser.parseString(b).getAsJsonArray();
                for (JsonElement e : arr) {
                    List<Line> s = parse(e.getAsJsonObject());
                    if (!s.isEmpty()) return s;
                }
                return List.<Line>of();
            });
        }).whenComplete((l, err) -> {
            if (gen != generation) return;
            lyrics = l == null ? List.of() : l;
            lyricsLoading = false;
        });
    }

    // ---- Musixmatch (desktop app API) -------------------------------------------------------------------

    private static final String MXM_BASE = "https://apic-desktop.musixmatch.com/ws/1.1/";
    private static final String MXM_APP = "web-desktop-app-v1.0";
    private static final String MXM_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final java.util.concurrent.ExecutorService MXM = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "Prism-Musixmatch");
        th.setDaemon(true);
        return th;
    });
    private static String mxmToken;
    /**
     * Musixmatch removed matcher.subtitle.get (Oct 2026: "endpoint not found" even for famous songs), and the newer
     * macro.subtitles.get serves scrambled anti-scraping text to guest tokens. After the first "endpoint not found"
     * stop asking for this session and go straight to LRCLIB.
     */
    private static volatile boolean mxmDead;

    private static Path mxmFile() { return mc.gameDirectory.toPath().resolve("prism").resolve("musixmatch.properties"); }

    private static JsonObject mxmGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", MXM_UA).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404 && r.body().contains("endpoint not found")) mxmDead = true;
        return r.statusCode() == 200 ? JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("message") : null;
    }

    private static int status(JsonObject msg) {
        try { return msg.getAsJsonObject("header").get("status_code").getAsInt(); } catch (Exception e) { return 500; }
    }

    /** The saved guest token, or a new one (only on first use or after a 401). */
    private static String token(boolean fresh) throws Exception {
        if (!fresh && mxmToken != null) return mxmToken;
        java.util.Properties p = new java.util.Properties();
        Path f = mxmFile();
        if (!fresh && Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) { p.load(in); }
            mxmToken = p.getProperty("token");
            if (mxmToken != null) return mxmToken;
        }
        JsonObject msg = mxmGet(MXM_BASE + "token.get?app_id=" + MXM_APP + "&t=" + System.currentTimeMillis());
        if (msg == null || status(msg) != 200) return null;
        mxmToken = msg.getAsJsonObject("body").get("user_token").getAsString();
        p.setProperty("token", mxmToken);
        Files.createDirectories(f.getParent());
        try (var out = Files.newOutputStream(f)) { p.store(out, "Prism Glass Musixmatch guest token (keep private)"); }
        return mxmToken;
    }

    private static List<Line> musixmatch(String track, String artist, boolean retry) {
        try {
            String tok = token(false);
            if (tok == null) return List.of();
            JsonObject msg = mxmGet(MXM_BASE + "matcher.subtitle.get?app_id=" + MXM_APP + "&q_track=" + q(track) + "&q_artist=" + q(artist)
                + "&usertoken=" + q(tok) + "&subtitle_format=lrc&format=json");
            if (msg == null) return List.of();
            int st = status(msg);
            if (st == 401 && retry) { mxmToken = null; return token(true) == null ? List.of() : musixmatch(track, artist, false); }
            if (st != 200) return List.of();
            String lrc = msg.getAsJsonObject("body").getAsJsonObject("subtitle").get("subtitle_body").getAsString();
            JsonObject fake = new JsonObject();
            fake.addProperty("syncedLyrics", lrc);
            return parse(fake);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Raw title, then with version junk peeled off ("(sped up)", " - 2011 Remaster", any brackets). */
    private static List<String> titleCandidates(String title) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String c : new String[]{title,
            title.replaceAll("(?i)\\s*[(\\[][^)\\]]*\\b(nightcore|sped\\s*up|slowed|reverb|remix|edit|version|remaster(?:ed)?|bootleg|cover|live|acoustic|instrumental|extended|radio|mix|feat\\.?|ft\\.?)\\b[^)\\]]*[)\\]]", ""),
            title.replaceAll("(?i)\\s+-\\s+.*$", ""),
            title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "")}) {
            if (c != null && !c.isBlank()) out.add(c.trim());
        }
        return new ArrayList<>(out);
    }

    private static List<Line> parse(JsonObject o) {
        String synced = o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull() ? o.get("syncedLyrics").getAsString() : "";
        List<Line> out = new ArrayList<>();
        for (String raw : synced.split("\n")) {
            Matcher m = LRC.matcher(raw.trim());
            if (!m.matches()) continue;
            long ms = Long.parseLong(m.group(1)) * 60_000 + Math.round(Double.parseDouble(m.group(2)) * 1000);
            out.add(new Line(ms, m.group(3).trim()));
        }
        // LRCLIB has some junk uploads (one "probe" line...): only trust a real sheet
        long words = out.stream().filter(l -> !l.text().isEmpty()).count();
        return words >= 5 ? out : List.of();
    }

    private static java.util.concurrent.CompletableFuture<String> get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
            .header("User-Agent", "PrismGlass/" + Prism.VERSION + " (minecraft mod)").GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(r -> r.statusCode() == 200 ? r.body() : null)
            .exceptionally(e -> null);
    }

    // ---- cover (iTunes search, PNG rendition) ----------------------------------------------------------

    private static void fetchCover(int gen, String t, String a) {
        get("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + q(a + " " + t)).thenCompose(body -> {
            if (body == null) return java.util.concurrent.CompletableFuture.<byte[]>completedFuture(null);
            JsonArray res = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("results");
            if (res == null || res.isEmpty()) return java.util.concurrent.CompletableFuture.<byte[]>completedFuture(null);
            String art = res.get(0).getAsJsonObject().get("artworkUrl100").getAsString();
            // Apple's image server renders any size/format from the URL: ask for a 96px PNG (NativeImage reads PNG only)
            String png = art.replaceAll("/[^/]+$", "/96x96bb.png");
            HttpRequest req = HttpRequest.newBuilder(URI.create(png)).timeout(Duration.ofSeconds(10)).GET().build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenApply(r -> r.statusCode() == 200 ? r.body() : null);
        }).exceptionally(e -> null).thenAccept(bytes -> {
            if (bytes == null || gen != generation) return;
            mc.execute(() -> {
                if (gen != generation || coverFromSession) return;
                try {
                    NativeImage img = NativeImage.read(bytes);
                    coverW = img.getWidth();
                    coverH = img.getHeight();
                    mc.getTextureManager().register(COVER, new DynamicTexture(() -> "prism now playing cover", img));
                    hasCover = true;
                } catch (Exception e) {
                    hasCover = false;
                }
            });
        });
    }
}
