package dev.prismglass.module.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Translates chat. Incoming messages get a translation line under them (or are replaced); optionally your own
 * messages are translated before sending. Uses Google Translate's public web endpoint (no key); message text is
 * sent to Google, nothing is logged.
 */
public class AutoTranslate extends Module {
    /** Display name -> Google language code. "Auto" only makes sense as a source. */
    private static final Map<String, String> LANGS = new LinkedHashMap<>();

    static {
        String[][] l = {{"Auto", "auto"}, {"English", "en"}, {"Polish", "pl"}, {"Spanish", "es"}, {"German", "de"}, {"French", "fr"},
            {"Russian", "ru"}, {"Ukrainian", "uk"}, {"Portuguese", "pt"}, {"Italian", "it"}, {"Turkish", "tr"}, {"Dutch", "nl"},
            {"Swedish", "sv"}, {"Norwegian", "no"}, {"Danish", "da"}, {"Finnish", "fi"}, {"Czech", "cs"}, {"Slovak", "sk"},
            {"Hungarian", "hu"}, {"Romanian", "ro"}, {"Greek", "el"}, {"Lithuanian", "lt"}, {"Latvian", "lv"}, {"Estonian", "et"},
            {"Arabic", "ar"}, {"Hebrew", "iw"}, {"Hindi", "hi"}, {"Indonesian", "id"}, {"Vietnamese", "vi"}, {"Thai", "th"},
            {"Japanese", "ja"}, {"Korean", "ko"}, {"Chinese", "zh-CN"}};
        for (String[] p : l) LANGS.put(p[0], p[1]);
    }

    private static String[] names(boolean withAuto) {
        return LANGS.keySet().stream().filter(n -> withAuto || !n.equals("Auto")).toArray(String[]::new);
    }

    public final ModeSetting from = mode("From", "Auto", "Language of incoming messages; Auto detects it.", names(true));
    public final ModeSetting to = mode("To", "English", "Language to translate incoming messages into.", names(false));
    public final ModeSetting show = mode("Show", "Below", "Below = translation line under the message. Replace = only the translation.", "Below", "Replace");
    public final BoolSetting players = bool("PlayerChat", true, "Translate signed player chat.");
    public final BoolSetting system = bool("ServerMessages", true, "Translate server/system messages (many servers send chat this way, 2b2t too).");
    public final BoolSetting outgoing = bool("Outgoing", false, "Translate your own chat before sending it.");
    public final ModeSetting outgoingTo = mode("OutgoingTo", "English", "Language your messages are sent in.", names(false));
    public final BoolSetting streamSafe = bool("StreamSafe", true,
        "With StreamProof on, show translations above chat in the hidden layer instead of in chat (viewers never see them).");

    /** Translations held in the hidden layer while StreamProof runs: shown above chat, newest at the bottom. */
    private record HiddenLine(Component text, long time) {}
    private final java.util.ArrayDeque<HiddenLine> hiddenLines = new java.util.ArrayDeque<>();
    private static final long HIDDEN_LINE_MS = 15_000;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Prism AutoTranslate");
        t.setDaemon(true);
        return t;
    });
    /** Small LRU cache: chat repeats a lot (spam, join messages). */
    private static final Map<String, Result> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Result> e) { return size() > 256; }
    };

    private record Result(String text, String detected) {}

    private static boolean hooked;
    private static volatile boolean sendingOwn; // our re-send of a translated message must not be translated again

    public AutoTranslate() {
        super("AutoTranslate", "Translates chat (From: Auto detects the language).", Category.CLIENT);
        if (!hooked) {
            hooked = true;
            ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) -> {
                AutoTranslate m = instance();
                if (m == null || !m.players.get()) return true;
                if (sender != null && mc.player != null && sender.id().equals(mc.player.getUUID())) return true;
                return m.incoming(message);
            });
            ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
                AutoTranslate m = instance();
                if (m == null || overlay || !m.system.get()) return true;
                return m.incoming(message);
            });
            ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
                AutoTranslate m = instance();
                if (m == null || !m.outgoing.get() || sendingOwn) return true;
                return m.outgoingMessage(message);
            });
        }
    }

    private static AutoTranslate instance() {
        if (Prism.modules() == null) return null;
        AutoTranslate m = Prism.modules().get(AutoTranslate.class);
        return m != null && m.isEnabled() ? m : null;
    }

    // ---- incoming ------------------------------------------------------------------------------------------

    /** @return whether vanilla should still show the original */
    private boolean incoming(Component message) {
        String full = message.getString();
        if (full.contains("Prism »")) return true;
        // "<name> message" (vanilla-style / most servers): translate only the message, keep the name
        java.util.regex.Matcher named = NAMED.matcher(full);
        String who = named.matches() ? "<" + named.group(1) + "> " : "";
        String text = named.matches() ? named.group(2) : full;
        if (!worthTranslating(text)) return true;
        String sl = LANGS.get(from.get()), tl = LANGS.get(to.get());
        boolean replace = show.is("Replace");
        translate(text, sl, tl).thenAccept(r -> mc.execute(() -> {
            boolean same = r == null || r.detected().equalsIgnoreCase(tl) || r.text().equalsIgnoreCase(text);
            if (replace) {
                // the original was held back: show the translation (or the original if nothing changed)
                if (same) mc.gui.getChat().addClientSystemMessage(message);
                else output(Component.literal("").append(tag(r.detected(), tl)).append(Component.literal(who + r.text())));
            } else if (!same) {
                output(Component.literal("  ⤷ ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(tag(r.detected(), tl))
                    .append(Component.literal(r.text()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
            }
        }));
        return !replace;
    }

    private static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile("^<([^>]{1,32})> (.+)$", java.util.regex.Pattern.DOTALL);

    /** Any-language text into the language with this display name ("English"...). Null on failure. Line breaks are kept. */
    public static CompletableFuture<String> translateTo(String text, String languageName) {
        return translate(text, "auto", LANGS.getOrDefault(languageName, "en")).thenApply(r -> r == null ? null : r.text());
    }

    public static String[] languages() { return names(false); }

    /** Dev test hook: translate a fixed sample sentence. */
    public static CompletableFuture<String> debugTranslate(String text, String tl) {
        return translate(text, "auto", tl).thenApply(r -> r == null ? "FAILED" : "[" + r.detected() + "] " + r.text());
    }

    /** Chat normally; the hidden layer while StreamProof is on (StreamSafe). */
    private void output(Component line) {
        if (streamSafe.get() && dev.prismglass.streamproof.Overlay.active()) {
            synchronized (hiddenLines) {
                hiddenLines.addLast(new HiddenLine(line, System.currentTimeMillis()));
                while (hiddenLines.size() > 6) hiddenLines.removeFirst();
            }
        } else {
            mc.gui.getChat().addClientSystemMessage(line);
        }
    }

    @Override
    public void onRender2D(net.minecraft.client.gui.GuiGraphicsExtractor ctx, float delta) {
        List<HiddenLine> lines;
        synchronized (hiddenLines) {
            long now = System.currentTimeMillis();
            hiddenLines.removeIf(l -> now - l.time() > HIDDEN_LINE_MS);
            if (hiddenLines.isEmpty()) return;
            lines = new ArrayList<>(hiddenLines);
        }
        // just above where chat sits (its height/scale from the chat settings)
        double scale = mc.options.chatScale().get();
        int chatHeight = net.minecraft.client.gui.components.ChatComponent.getHeight(mc.options.chatHeightUnfocused().get());
        float maxW = 0;
        for (HiddenLine l : lines) maxW = Math.max(maxW, mc.font.width(l.text()));
        float w = maxW + 12, h = lines.size() * 10 + 6;
        float x = 4, y = (float) (ctx.guiHeight() - 40 - chatHeight * scale - h - 4);
        long now = System.currentTimeMillis();
        dev.prismglass.gui.render.Glass.panel(ctx, x, y, w, h, Prism.modules().get(ClickGui.class).panelStyle());
        float ly = y + 4;
        for (HiddenLine l : lines) {
            float age = (now - l.time()) / (float) HIDDEN_LINE_MS;
            int alpha = age > 0.85f ? (int) (255 * (1 - age) / 0.15f) : 255;
            ctx.text(mc.font, l.text(), (int) x + 6, (int) ly, dev.prismglass.util.ColorUtil.withAlpha(0xFFFFFFFF, Math.max(8, alpha)), false);
            ly += 10;
        }
    }

    private static Component tag(String fromCode, String toCode) {
        return Component.literal("[" + fromCode + "→" + toCode + "] ").withStyle(ChatFormatting.AQUA);
    }

    private static boolean worthTranslating(String text) {
        if (text.length() < 2) return false;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) letters++;
        return letters >= 2;
    }

    // ---- outgoing ------------------------------------------------------------------------------------------

    /** @return whether to send the original now (false: it's sent translated once ready) */
    private boolean outgoingMessage(String message) {
        if (message.startsWith("/") || message.startsWith(Prism.commands().getPrefix()) || !worthTranslating(message)) return true;
        String tl = LANGS.get(outgoingTo.get());
        translate(message, "auto", tl).thenAccept(r -> mc.execute(() -> {
            if (mc.getConnection() == null) return;
            String out = r == null || r.detected().equalsIgnoreCase(tl) ? message : r.text();
            if (out.length() > 256) out = out.substring(0, 256);
            sendingOwn = true;
            try {
                mc.getConnection().sendChat(out);
            } finally {
                sendingOwn = false;
            }
        }));
        return false;
    }

    // ---- service -------------------------------------------------------------------------------------------

    private static CompletableFuture<Result> translate(String text, String sl, String tl) {
        String key = sl + "|" + tl + "|" + text;
        synchronized (CACHE) {
            Result cached = CACHE.get(key);
            if (cached != null) return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            // the Chrome-dictionary endpoint is far less rate-limited than translate_a/single (which 429s quickly)
            Result r = viaDictChrome(text, sl, tl);
            if (r == null) r = viaGtx(text, sl, tl);
            if (r != null) synchronized (CACHE) { CACHE.put(key, r); }
            return r;
        }, POOL);
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
            .header("User-Agent", "Mozilla/5.0").GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            Prism.LOG.warn("[translate] HTTP {}", res.statusCode()); // never log the message itself
            return null;
        }
        return res.body();
    }

    /** sl=auto: [["text","detected"]]; fixed sl: ["text"]. */
    private static Result viaDictChrome(String text, String sl, String tl) {
        try {
            String body = get("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=" + sl + "&tl=" + tl
                + "&q=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
            if (body == null) return null;
            JsonArray root = JsonParser.parseString(body).getAsJsonArray();
            if (root.isEmpty()) return null;
            var first = root.get(0);
            if (first.isJsonArray()) return new Result(first.getAsJsonArray().get(0).getAsString(), first.getAsJsonArray().get(1).getAsString());
            return new Result(first.getAsString(), sl);
        } catch (Exception e) {
            Prism.LOG.warn("[translate] request failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** [[["text","orig",...],...], null, "detected", ...] */
    private static Result viaGtx(String text, String sl, String tl) {
        try {
            String body = get("https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=" + sl + "&tl=" + tl
                + "&q=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
            if (body == null) return null;
            JsonArray root = JsonParser.parseString(body).getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (var seg : root.get(0).getAsJsonArray()) {
                var a = seg.getAsJsonArray();
                if (!a.get(0).isJsonNull()) sb.append(a.get(0).getAsString());
            }
            String detected = root.size() > 2 && !root.get(2).isJsonNull() ? root.get(2).getAsString() : sl;
            return new Result(sb.toString(), detected);
        } catch (Exception e) {
            Prism.LOG.warn("[translate] request failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public String getInfo() { return from.get() + " → " + to.get(); }
}
