package dev.prismglass.config;

import com.google.gson.*;
import dev.prismglass.Prism;
import dev.prismglass.gui.ClickGuiScreen;
import dev.prismglass.module.Module;
import dev.prismglass.setting.ColorSetting;
import dev.prismglass.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * JSON profiles in .minecraft/prism/profiles/<name>.json. The active profile name and global
 * data (friends, prefix) live in .minecraft/prism/prism.json.
 */
public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root = FabricLoader.getInstance().getGameDir().resolve("prism");
    private final Path profiles = root.resolve("profiles");
    private String active = "default";

    public String getActive() { return active; }

    public void load() {
        try {
            Files.createDirectories(profiles);
            Path global = root.resolve("prism.json");
            if (Files.exists(global)) {
                JsonObject o = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("profile")) active = o.get("profile").getAsString();
                if (o.has("prefix")) Prism.commands().setPrefix(o.get("prefix").getAsString());
                if (o.has("friends")) for (JsonElement f : o.getAsJsonArray("friends")) Prism.friends().add(f.getAsString());
            }
            loadProfile(active);
        } catch (Exception e) {
            Prism.LOG.error("Failed to load config", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(profiles);
            JsonObject o = new JsonObject();
            o.addProperty("profile", active);
            o.addProperty("prefix", Prism.commands().getPrefix());
            JsonArray friends = new JsonArray();
            synchronized (Prism.friends().all()) { Prism.friends().all().forEach(friends::add); }
            o.add("friends", friends);
            write(root.resolve("prism.json"), o);
            saveProfile(active);
        } catch (Exception e) {
            Prism.LOG.error("Failed to save config", e);
        }
    }

    public boolean loadProfile(String name) {
        Path file = profiles.resolve(sanitize(name) + ".json");
        if (!Files.exists(file)) return false;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject mods = o.has("modules") ? o.getAsJsonObject("modules") : new JsonObject();
            for (Module m : Prism.modules().all()) {
                if (!mods.has(m.getName())) continue;
                JsonObject mo = mods.getAsJsonObject(m.getName());
                if (mo.has("settings")) {
                    JsonObject so = mo.getAsJsonObject("settings");
                    for (Setting<?> s : m.getSettings()) {
                        if (so.has(s.getName())) {
                            try { s.load(so.get(s.getName())); } catch (Exception ignored) {}
                        }
                    }
                }
                if (mo.has("enabled")) m.setEnabledSilently(mo.get("enabled").getAsBoolean());
            }
            if (o.has("gui")) ClickGuiScreen.loadLayout(o.getAsJsonObject("gui"));
            active = sanitize(name);
            return true;
        } catch (Exception e) {
            Prism.LOG.error("Failed to load profile {}", name, e);
            return false;
        }
    }

    public void saveProfile(String name) throws IOException {
        JsonObject o = new JsonObject();
        JsonObject mods = new JsonObject();
        for (Module m : Prism.modules().all()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("enabled", m.isEnabled());
            JsonObject so = new JsonObject();
            for (Setting<?> s : m.getSettings()) so.add(s.getName(), s.save());
            mo.add("settings", so);
            mods.add(m.getName(), mo);
        }
        o.add("modules", mods);
        o.add("gui", ClickGuiScreen.saveLayout());
        write(profiles.resolve(sanitize(name) + ".json"), o);
    }

    public void switchTo(String name) throws IOException {
        saveProfile(active);
        active = sanitize(name);
        if (!loadProfile(active)) saveProfile(active);
    }

    private static final String CODE_PREFIX = "PRISM1:";

    /**
     * A profile as one paste-able line: PRISM1: + base64(gzip(json)), holding only what differs from defaults
     * (enabled modules, changed settings) so it fits a Discord message. Friends, prefix and GUI layout stay private.
     */
    public String exportCode(String name) throws IOException {
        String clean = sanitize(name);
        if (clean.equals(active)) saveProfile(active); // share what's on screen right now
        Path file = profiles.resolve(clean + ".json");
        if (!Files.exists(file)) throw new IOException("no profile " + clean);
        JsonObject full = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject mods = full.has("modules") ? full.getAsJsonObject("modules") : new JsonObject();
        JsonObject diff = new JsonObject();
        for (Module m : Prism.modules().all()) {
            if (!mods.has(m.getName())) continue;
            JsonObject mo = mods.getAsJsonObject(m.getName()), out = new JsonObject();
            if (mo.has("enabled") && mo.get("enabled").getAsBoolean()) out.addProperty("e", true);
            JsonObject so = mo.has("settings") ? mo.getAsJsonObject("settings") : new JsonObject(), changed = new JsonObject();
            for (Setting<?> st : m.getSettings()) {
                if (so.has(st.getName()) && !so.get(st.getName()).equals(st.saveDefault())) changed.add(st.getName(), so.get(st.getName()));
            }
            if (!changed.isEmpty()) out.add("s", changed);
            if (!out.isEmpty()) diff.add(m.getName(), out);
        }
        byte[] json = new Gson().toJson(diff).getBytes(StandardCharsets.UTF_8);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(bytes)) { gz.write(json); }
        return CODE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    private static final String MODULE_CODE_PREFIX = "PRISMM1:";

    /** Settings of just these modules (changed-from-default only) as a code; enabled state isn't included. */
    public String exportModules(List<Module> modules) throws IOException {
        JsonObject diff = new JsonObject();
        for (Module m : modules) {
            JsonObject changed = new JsonObject();
            for (Setting<?> st : m.getSettings()) {
                JsonElement now = st.save();
                if (!now.equals(st.saveDefault())) changed.add(st.getName(), now);
            }
            diff.add(m.getName(), changed);
        }
        byte[] json = new Gson().toJson(diff).getBytes(StandardCharsets.UTF_8);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(bytes)) { gz.write(json); }
        return MODULE_CODE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    /**
     * Applies a module code to the live modules: each module in it is reset to defaults, then gets the code's values.
     * Only modules in {@code only} (null = all in the code) are touched. Returns the names applied.
     */
    public List<String> importModules(String code, java.util.Set<String> only) throws IOException {
        String c = code == null ? "" : code.trim();
        if (!c.startsWith(MODULE_CODE_PREFIX)) throw new IOException("not a Prism module code");
        JsonObject diff;
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(
                java.util.Base64.getUrlDecoder().decode(c.substring(MODULE_CODE_PREFIX.length()))))) {
            diff = JsonParser.parseString(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IllegalArgumentException e) {
            throw new IOException("broken code");
        }
        List<String> applied = new ArrayList<>();
        for (Module m : Prism.modules().all()) {
            if (!diff.has(m.getName()) || (only != null && !only.contains(m.getName()))) continue;
            JsonObject changed = diff.getAsJsonObject(m.getName());
            for (Setting<?> st : m.getSettings()) {
                st.reset();
                if (changed.has(st.getName())) {
                    try { st.load(changed.get(st.getName())); } catch (Exception ignored) {}
                }
            }
            applied.add(m.getName());
        }
        return applied;
    }

    /** Stores a shared code as a new profile (defaults + the code's changes; name made unique) and returns its name. */
    public String importCode(String code, String wanted) throws IOException {
        String c = code == null ? "" : code.trim();
        if (!c.startsWith(CODE_PREFIX)) throw new IOException("not a Prism config code");
        byte[] json;
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(
                java.util.Base64.getUrlDecoder().decode(c.substring(CODE_PREFIX.length()))))) {
            json = in.readNBytes(4 << 20);
        } catch (IllegalArgumentException e) {
            throw new IOException("broken code");
        }
        JsonObject diff = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject mods = new JsonObject();
        for (Module m : Prism.modules().all()) {
            JsonObject d = diff.has(m.getName()) ? diff.getAsJsonObject(m.getName()) : new JsonObject();
            JsonObject changed = d.has("s") ? d.getAsJsonObject("s") : new JsonObject();
            JsonObject mo = new JsonObject(), so = new JsonObject();
            mo.addProperty("enabled", d.has("e") && d.get("e").getAsBoolean());
            for (Setting<?> st : m.getSettings()) so.add(st.getName(), changed.has(st.getName()) ? changed.get(st.getName()) : st.saveDefault());
            mo.add("settings", so);
            mods.add(m.getName(), mo);
        }
        JsonObject profile = new JsonObject();
        profile.add("modules", mods);
        profile.add("gui", ClickGuiScreen.saveLayout()); // keep your own panel layout
        String base = sanitize(wanted == null || wanted.isBlank() ? "shared" : wanted), name = base;
        for (int i = 2; Files.exists(profiles.resolve(name + ".json")); i++) name = base + "-" + i;
        Files.createDirectories(profiles);
        write(profiles.resolve(name + ".json"), profile);
        return name;
    }

    /** .minecraft/prism, for the GUI's "Open folder". */
    public Path folder() { return root; }

    /** Deletes a stored profile; the active one can't be deleted. */
    public boolean delete(String name) {
        String clean = sanitize(name);
        if (clean.equals(active)) return false;
        try { return Files.deleteIfExists(profiles.resolve(clean + ".json")); }
        catch (IOException e) { Prism.LOG.error("Failed to delete profile {}", name, e); return false; }
    }

    public static String clean(String name) { return sanitize(name); }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(profiles)) {
            s.filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> out.add(p.getFileName().toString().replace(".json", "")));
        } catch (IOException ignored) {}
        if (!out.contains(active)) out.add(active);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static void write(Path file, JsonObject o) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sanitize(String name) { return name.replaceAll("[^A-Za-z0-9_-]", "_"); }

    @SuppressWarnings("unused")
    private static boolean isColor(Setting<?> s) { return s instanceof ColorSetting; }
}
