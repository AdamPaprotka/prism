package dev.prismglass.manager;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.prismglass.Prism;
import dev.prismglass.setting.KeySetting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.KeyUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Macros: a named list of lines run one per tick when its key is pressed (or with {@code .macro run}).
 * A line is a Prism command ({@code .t killaura}), a server command ({@code /home}), {@code !wait <ticks>}, or chat.
 * Macros are global (prism/macros.json), not per profile, and their keys show in ClickGUI → Keybinds.
 * Macro text is never written to the log.
 */
public final class Macros {
    public static final class Macro {
        public final String name;
        public final KeySetting key;
        public final List<String> lines = new ArrayList<>();

        Macro(String name) {
            this.name = name;
            this.key = new KeySetting(name, "Key that runs the macro.", KeyUtil.NONE);
        }
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final Path file = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("macros.json");
    private final Map<String, Macro> macros = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Deque<String> queue = new ArrayDeque<>();
    private int waitTicks;
    /** Lines sent by the last run (dev tests read it instead of the server). */
    public static final List<String> DEV_SENT = new ArrayList<>();
    public static boolean devDryRun;

    public List<Macro> all() { synchronized (macros) { return new ArrayList<>(macros.values()); } }

    public Macro get(String name) { return macros.get(name.toLowerCase(Locale.ROOT)); }

    public Macro getOrCreate(String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Macro names: letters, digits, _ and -, up to 32");
        return macros.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Macro(name));
    }

    public boolean remove(String name) { return macros.remove(name.toLowerCase(Locale.ROOT)) != null; }

    public boolean running() { return !queue.isEmpty(); }

    public void stop() { queue.clear(); waitTicks = 0; }

    /** Queues the macro's lines; a macro started while another runs replaces it. */
    public boolean run(String name) {
        Macro m = get(name);
        if (m == null || m.lines.isEmpty()) return false;
        stop();
        queue.addAll(m.lines);
        tick(); // first line goes out on the press itself
        return true;
    }

    /** Main thread, every client tick with a world loaded. */
    public void tick() {
        if (queue.isEmpty() || mc.player == null || mc.getConnection() == null) { if (mc.player == null) stop(); return; }
        if (waitTicks > 0) { waitTicks--; return; }
        String line = queue.poll();
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("!wait")) {
            try { waitTicks = Math.max(0, Integer.parseInt(trimmed.substring(5).trim()) - 1); } catch (NumberFormatException e) { waitTicks = 19; }
            return;
        }
        send(trimmed);
    }

    private void send(String line) {
        if (line.isEmpty()) return;
        if (devDryRun) { DEV_SENT.add(line); if (!line.startsWith(Prism.commands().getPrefix())) return; }
        String prefix = Prism.commands().getPrefix();
        if (!prefix.isEmpty() && line.startsWith(prefix)) Prism.commands().handle(line);
        else if (line.startsWith("/")) mc.getConnection().sendCommand(line.substring(1));
        else mc.getConnection().sendChat(line);
    }

    // ---- storage: prism/macros.json ------------------------------------------------------------------

    public void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.entrySet()) {
                JsonObject mo = e.getValue().getAsJsonObject();
                Macro m = getOrCreate(e.getKey());
                if (mo.has("key")) m.key.load(mo.get("key"));
                if (mo.has("lines")) for (JsonElement l : mo.getAsJsonArray("lines")) m.lines.add(l.getAsString());
            }
        } catch (Exception e) {
            Prism.LOG.error("Failed to load macros: {}", e.toString());
        }
    }

    public void save() {
        try {
            JsonObject o = new JsonObject();
            for (Macro m : all()) {
                JsonObject mo = new JsonObject();
                mo.add("key", m.key.save());
                JsonArray lines = new JsonArray();
                m.lines.forEach(lines::add);
                mo.add("lines", lines);
                o.add(m.name, mo);
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("macros.json.tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Prism.LOG.error("Failed to save macros: {}", e.toString());
        }
    }

    /** Chat summary for .macro list. */
    public void list() {
        if (macros.isEmpty()) { ChatUtil.info("No macros. .macro add <name> <line>"); return; }
        for (Macro m : all())
            ChatUtil.info("§f" + m.name + " §7[" + KeyUtil.name(m.key.get()) + "] §8" + m.lines.size() + " line" + (m.lines.size() == 1 ? "" : "s"));
    }
}
