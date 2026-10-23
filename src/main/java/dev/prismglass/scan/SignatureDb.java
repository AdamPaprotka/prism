package dev.prismglass.scan;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import dev.prismglass.Prism;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The signature database: the bundled {@code plugin_signatures.json} plus an optional
 * {@code prism/plugin_signatures.json} next to the config. Entries of the user file replace bundled ones with the same
 * id; {@code "remove": ["id"]} drops a bundled one. The files are relaxed JSON (comments, trailing commas, single
 * quotes are fine). {@code .plugins reload} re-reads them without restarting.
 */
public final class SignatureDb {
    public static final String RESOURCE = "/assets/prismglass/plugin_signatures.json";

    private final Map<String, Signature> byId = new LinkedHashMap<>();
    private final Map<String, Signature> byNs = new HashMap<>();
    private final Map<String, Signature> byName = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private int bundled, user;

    private SignatureDb() {}

    // ---- file format -----------------------------------------------------------------------------------

    private static final class RawShape {
        List<String> has;
        Integer min;
    }

    private static final class RawSig {
        String id, name, kind, note, ping, probe;
        Boolean builtin, bukkit;
        Integer priority;
        List<String> ns, cmds, weak, channels, brand, status, pack;
        List<RawShape> shapes;
    }

    private static final class RawFile {
        List<RawSig> signatures;
        List<String> remove;
    }

    public static SignatureDb load() {
        SignatureDb db = new SignatureDb();
        try (InputStream in = SignatureDb.class.getResourceAsStream(RESOURCE)) {
            if (in == null) db.problems.add("bundled signature file missing");
            else db.bundled = db.read(new InputStreamReader(in, StandardCharsets.UTF_8), "bundled");
        } catch (IOException e) {
            db.problems.add("bundled file: " + e.getMessage());
        }
        Path userFile = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("plugin_signatures.json");
        if (Files.isRegularFile(userFile)) {
            try (Reader r = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
                db.user = db.read(r, "prism/plugin_signatures.json");
            } catch (IOException e) {
                db.problems.add("prism/plugin_signatures.json: " + e.getMessage());
            }
        }
        db.index();
        for (String p : db.problems) Prism.LOG.warn("[PluginScanner] signatures: {}", p);
        return db;
    }

    /** Parses a signature file from text (tests use this to add entries). Returns how many signatures it loaded. */
    public static SignatureDb fromText(String json) {
        SignatureDb db = new SignatureDb();
        db.read(new StringReader(json), "text");
        db.index();
        return db;
    }

    /** A copy of this database with {@code extra} entries merged in (same id replaces). */
    public SignatureDb with(String extraJson) {
        SignatureDb db = new SignatureDb();
        db.byId.putAll(byId);
        db.problems.addAll(problems);
        db.read(new StringReader(extraJson), "extra");
        db.index();
        return db;
    }

    private int read(Reader reader, String origin) {
        RawFile file;
        try {
            java.io.StringWriter text = new java.io.StringWriter();
            reader.transferTo(text);
            JsonReader jr = new JsonReader(new StringReader(withoutTrailingCommas(text.toString())));
            jr.setLenient(true);
            file = new Gson().fromJson(jr, RawFile.class);
        } catch (RuntimeException | IOException e) {
            problems.add(origin + ": " + e.getMessage());
            return 0;
        }
        if (file == null) return 0;
        if (file.remove != null) for (String id : file.remove) byId.remove(id.toLowerCase(Locale.ROOT));
        int n = 0;
        if (file.signatures != null) {
            for (RawSig raw : file.signatures) {
                Signature sig = convert(raw, origin);
                if (sig != null) {
                    byId.put(sig.id(), sig);
                    n++;
                }
            }
        }
        return n;
    }

    /** Gson's lenient mode takes comments and single quotes but not a comma before } or ]: drop those, outside strings and comments. */
    static String withoutTrailingCommas(String in) {
        StringBuilder out = new StringBuilder(in.length());
        int lastComma = -1;
        char quote = 0;
        boolean line = false, block = false;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            char n = i + 1 < in.length() ? in.charAt(i + 1) : 0;
            if (line) {
                if (c == '\n') line = false;
            } else if (block) {
                if (c == '*' && n == '/') { out.append(c); c = n; i++; block = false; }
            } else if (quote != 0) {
                if (c == '\\' && n != 0) { out.append(c); c = n; i++; }
                else if (c == quote) quote = 0;
            } else if (c == '/' && n == '/' || c == '#') {
                line = true;
            } else if (c == '/' && n == '*') {
                block = true;
            } else if (c == '"' || c == '\'') {
                quote = c;
                lastComma = -1;
            } else if (c == ',') {
                lastComma = out.length();
            } else if (c == '}' || c == ']') {
                if (lastComma >= 0) out.setCharAt(lastComma, ' ');
                lastComma = -1;
            } else if (!Character.isWhitespace(c)) {
                lastComma = -1;
            }
            out.append(c);
        }
        return out.toString();
    }

    private Signature convert(RawSig r, String origin) {
        if (r == null || r.id == null || r.id.isBlank() || r.name == null) {
            problems.add(origin + ": entry without id/name skipped");
            return null;
        }
        String id = r.id.toLowerCase(Locale.ROOT);
        List<Signature.Shape> shapes = new ArrayList<>();
        if (r.shapes != null) {
            for (RawShape s : r.shapes) {
                if (s == null || s.has == null || s.has.isEmpty()) continue;
                List<String> has = lower(s.has);
                shapes.add(new Signature.Shape(has, Math.max(1, Math.min(has.size(), s.min == null ? has.size() : s.min))));
            }
        }
        return new Signature(id, r.name, r.kind == null ? "plugin" : r.kind.toLowerCase(Locale.ROOT),
            Boolean.TRUE.equals(r.builtin), Boolean.TRUE.equals(r.bukkit), r.priority == null ? 0 : r.priority,
            r.note == null ? "" : r.note, lower(r.ns), lower(r.cmds), lower(r.weak), shapes, lower(r.channels),
            patterns(r.brand, id), patterns(r.status, id), patterns(r.pack, id), r.ping, r.probe == null ? null : r.probe.toLowerCase(Locale.ROOT));
    }

    private static List<String> lower(List<String> in) {
        if (in == null) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }

    private List<Pattern> patterns(List<String> in, String id) {
        if (in == null) return List.of();
        List<Pattern> out = new ArrayList<>();
        for (String s : in) {
            try {
                out.add(Pattern.compile(s));
            } catch (PatternSyntaxException e) {
                problems.add(id + ": bad regex " + s);
            }
        }
        return List.copyOf(out);
    }

    private void index() {
        byNs.clear();
        byName.clear();
        for (Signature s : byId.values()) {
            for (String ns : s.ns()) byNs.putIfAbsent(ns, s);
            byName.putIfAbsent(s.name().toLowerCase(Locale.ROOT), s);
            byName.putIfAbsent(s.id(), s);
        }
    }

    // ---- lookups ---------------------------------------------------------------------------------------

    public Collection<Signature> all() { return Collections.unmodifiableCollection(byId.values()); }
    public Signature byId(String id) { return byId.get(id.toLowerCase(Locale.ROOT)); }
    public Signature byNamespace(String ns) { return byNs.get(ns.toLowerCase(Locale.ROOT)); }
    /** By plugin name as the server prints it ("WorldEdit") or by id. */
    public Signature byName(String name) { return byName.get(name.toLowerCase(Locale.ROOT)); }
    public List<String> problems() { return Collections.unmodifiableList(problems); }
    public int bundledCount() { return bundled; }
    public int userCount() { return user; }
    public int size() { return byId.size(); }
}
