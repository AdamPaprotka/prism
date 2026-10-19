package dev.prismglass.module.world;

import com.mojang.brigadier.tree.CommandNode;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ListSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.ChatUtil;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

/**
 * Lists the server's plugins. Bukkit registers every plugin command twice, "cmd" and "plugin:cmd", and the
 * namespaced copy ends up in the command tree the server sends on join (spigot.yml send-namespaced, default on)
 * and in the tab-completions of "/". The namespace is the plugin's name.
 *
 * <p>Passive: the command tree is read whenever the server (re)sends it. Active: a tab-completion of "/" is
 * requested, which also works on servers that trim the join tree. Probe (off by default): runs /bukkit:plugins
 * and reads the answer; that is a real command, so the server can log it.
 */
public class PluginScanner extends Module {
    private static final String[] DEFAULT_IGNORE = {"minecraft", "bukkit", "spigot", "paper", "purpur", "pufferfish",
        "folia", "velocity", "bungeecord", "waterfall", "fabric", "forge", "neoforge"};

    public final BoolSetting autoScanOnJoin = bool("AutoScanOnJoin", true, "Scan a moment after joining a server.");
    public final BoolSetting activeScan = bool("ActiveScan", true, "Also ask the server to tab-complete \"/\" (finds plugins the join tree leaves out).");
    public final BoolSetting probeCommands = bool("ProbeCommands", false, "Also run /bukkit:plugins and read the answer. A real command: the server can log it.");
    public final ListSetting ignoreList = add(new ListSetting("IgnoreList", "Namespaces that are the server itself, not plugins.",
        String.join(",", DEFAULT_IGNORE), ListSetting.Kind.TEXT, false, 0, List.of(DEFAULT_IGNORE)));
    public final BoolSetting showBrand = bool("ShowBrand", true, "Print the server brand (what F3 shows) with the list.");
    public final BoolSetting chatOutput = bool("ChatOutput", true, "Print the result in chat (hover a plugin for its commands, click the list to copy it).");
    public final BoolSetting hud = bool("Hud", false, "Show the plugin list on the HUD (drag it in the HUD editor).");
    public final NumberSetting hudLines = num("HudLines", 12, 1, 40, 1, "Plugins shown on the HUD before \"+n more\".").visibleWhen(hud::get);

    /** Used in the tab-complete request so the reply can be told apart from the chat box's own. */
    private static final int SCAN_ID = 0x5052_534E; // "PRSN"
    private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

    // netty -> main thread hand-over
    private final ConcurrentLinkedQueue<List<String>> completions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> probeChat = new ConcurrentLinkedQueue<>();
    private volatile boolean treeDirty;
    private volatile long probeUntil;

    /** lowercase namespace -> display name + commands. */
    private final Map<String, Found> found = new TreeMap<>();
    private final Set<String> sources = new LinkedHashSet<>();
    private String brand;
    private int joinTicks = -1, reportTicks = -1;
    private boolean probeOnce;

    private record Found(String name, Set<String> commands) {}

    public PluginScanner() { super("PluginScanner", "Finds out which plugins the server runs.", Category.WORLD); }

    @Override
    public void onWorldJoin() {
        reset();
        if (autoScanOnJoin.get()) joinTicks = 0;
    }

    @Override public void onEnable() { if (!nullCheck()) { reset(); joinTicks = 0; } }

    private void reset() {
        found.clear();
        sources.clear();
        completions.clear();
        probeChat.clear();
        brand = null;
        treeDirty = false;
        probeUntil = 0;
        joinTicks = -1;
        reportTicks = -1;
    }

    // ---- scanning ------------------------------------------------------------------------------------

    /** Start a scan now; the result is printed once the server has answered (about a second). */
    public void scan(boolean probe) {
        if (nullCheck() || mc.getConnection() == null) { ChatUtil.error("Not on a server"); return; }
        found.clear();
        sources.clear();
        readTree();
        int wait = 0;
        if (activeScan.get()) {
            mc.getConnection().send(new ServerboundCommandSuggestionPacket(SCAN_ID, "/"));
            wait = 30;
        }
        if (probe || probeCommands.get()) {
            probeOnce = true;
            probeUntil = System.currentTimeMillis() + 2500;
            mc.getConnection().sendCommand("bukkit:plugins");
            wait = 60;
        }
        reportTicks = wait;
    }

    /** Root literals of the command tree the server sent. */
    private void readTree() {
        ClientPacketListener net = mc.getConnection();
        if (net == null) return;
        for (CommandNode<?> node : net.getCommands().getRoot().getChildren()) addCommand(node.getName(), "tree");
    }

    private void addCommand(String name, String source) {
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1) return;
        String ns = name.substring(0, colon).toLowerCase(Locale.ROOT);
        if (ignored(ns) || !PLUGIN_NAME.matcher(ns).matches()) return;
        found.computeIfAbsent(ns, k -> new Found(ns, new TreeSet<>())).commands().add(name.substring(colon + 1));
        sources.add(source);
    }

    private void addPlugin(String name, String source) {
        String ns = name.toLowerCase(Locale.ROOT);
        if (ignored(ns)) return;
        Found old = found.get(ns);
        // /plugins gives the real capitalisation: keep that as the display name
        found.put(ns, new Found(name, old == null ? new TreeSet<>() : old.commands()));
        sources.add(source);
    }

    private boolean ignored(String ns) {
        for (String i : ignoreList.items()) if (i.equalsIgnoreCase(ns)) return true;
        return false;
    }

    /**
     * Bukkit: "Plugins (3): A, B, C". Paper: "Server Plugins (3):" then "Bukkit Plugins:" / "Paper Plugins:" and
     * " - A, B, C" lines, in one or several messages. Anything comma-separated after a header counts.
     */
    private void parseProbe(String text) {
        boolean inList = false;
        String plain = net.minecraft.ChatFormatting.stripFormatting(text); // Bukkit colours the list with legacy codes
        for (String raw : (plain == null ? text : plain).split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("plugins (") || lower.endsWith("plugins:")) {
                inList = true;
                int c = line.indexOf(':');
                line = c < 0 ? "" : line.substring(c + 1).strip();
                if (line.isEmpty()) continue;
            } else if (lower.contains("unknown command") || lower.contains("no permission") || lower.contains("don't have permission")) {
                sources.add("probe denied");
                continue;
            }
            if (!inList) continue;
            if (line.startsWith("-")) line = line.substring(1).strip();
            for (String part : line.split(",")) {
                Matcher m = PLUGIN_NAME.matcher(part.strip());
                if (m.find() && !m.group().equalsIgnoreCase("none")) addPlugin(m.group(), "probe");
            }
        }
    }

    @Override
    public void onTick() {
        if (joinTicks >= 0 && ++joinTicks == 60) { joinTicks = -1; scan(false); }
        if (treeDirty) {
            treeDirty = false;
            readTree();
            if (reportTicks < 0 && joinTicks < 0 && autoScanOnJoin.get()) reportTicks = 5; // a resent tree: refresh quietly
        }
        List<String> names;
        while ((names = completions.poll()) != null) for (String n : names) addCommand(n, "tab");
        String chat;
        while ((chat = probeChat.poll()) != null) parseProbe(chat);
        if (reportTicks >= 0 && --reportTicks < 0) report();
    }

    private void report() {
        if (mc.getConnection() != null) brand = mc.getConnection().serverBrand();
        probeOnce = false;
        if (!chatOutput.get()) return;
        if (showBrand.get()) ChatUtil.info("Server brand: §f" + (brand == null ? "unknown" : brand));
        if (found.isEmpty()) {
            String hint = sources.contains("probe denied") ? " (/plugins is blocked)" : activeScan.get() ? "" : " (try ActiveScan or ProbeCommands)";
            ChatUtil.info("No plugins detected: vanilla, a proxy, or namespaced commands are hidden" + hint);
            return;
        }
        MutableComponent line = Component.literal("Plugins (" + found.size() + "): ").withStyle(ChatFormatting.GRAY);
        List<String> plain = new ArrayList<>();
        boolean first = true;
        for (Found f : found.values()) {
            if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            first = false;
            plain.add(f.name());
            String cmds = f.commands().isEmpty() ? "no namespaced commands seen" : String.join(", ", f.commands());
            Component hover = Component.literal(f.name()).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("\n" + cmds).withStyle(ChatFormatting.GRAY));
            line.append(Component.literal(f.name()).withStyle(s -> s.withColor(ChatFormatting.WHITE)
                .withHoverEvent(new HoverEvent.ShowText(hover))));
        }
        String copy = String.join(", ", plain);
        line.withStyle(s -> s.withClickEvent(new ClickEvent.CopyToClipboard(copy)));
        if (dev.prismglass.streamproof.Overlay.active()) {
            // chat is captured while StreamProof is on: fall back to the plain toast path
            ChatUtil.info("Plugins (" + found.size() + "): §f" + copy);
        } else {
            ChatUtil.send(Component.literal("").append(Component.literal("◆ ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("Prism ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY)).append(line));
        }
        ChatUtil.info("§8From " + String.join(" + ", sources) + ". Hover a name for its commands, click the list to copy it.");
    }

    /** Names found so far, sorted. */
    public List<String> plugins() {
        List<String> out = new ArrayList<>();
        for (Found f : found.values()) out.add(f.name());
        return out;
    }

    public String brand() { return brand; }

    /** Where the names came from: tree, tab, probe. */
    public Set<String> sources() { return Collections.unmodifiableSet(sources); }

    // ---- packets (netty thread: only hand data over) ----------------------------------------------------

    @Override
    public void onPacketReceive(PacketEvent e) {
        if (e.packet instanceof ClientboundCommandsPacket) {
            treeDirty = true;
        } else if (e.packet instanceof ClientboundCommandSuggestionsPacket p && p.id() == SCAN_ID) {
            List<String> names = new ArrayList<>();
            for (ClientboundCommandSuggestionsPacket.Entry s : p.suggestions()) names.add(s.text());
            completions.add(names);
            e.cancel(); // ours, not the chat box's
        } else if (probeOnce && e.packet instanceof ClientboundSystemChatPacket p && !p.overlay() && System.currentTimeMillis() < probeUntil) {
            probeChat.add(p.content().getString());
        }
    }

    // ---- HUD -------------------------------------------------------------------------------------------

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (!hud.get() || (mc.options.hideGui && !Hud.editing)) return;
        List<String> lines = new ArrayList<>();
        lines.add("Plugins §f" + found.size() + (brand == null || !showBrand.get() ? "" : " §8" + brand));
        int max = hudLines.getInt(), i = 0;
        for (Found f : found.values()) {
            if (i++ >= max) { lines.add("§8+" + (found.size() - max) + " more"); break; }
            lines.add("§f" + f.name());
        }
        float w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        w += 12;
        float h = lines.size() * 10 + 6;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        Hud hudModule = Prism.modules().get(Hud.class);
        float[] p = hudModule != null && hudModule.isEnabled() ? hudModule.place("Plugins", w, h, 4, 26, sw, sh) : new float[]{4, 26};
        Glass.panel(ctx, p[0], p[1], w, h, Prism.modules().get(ClickGui.class).panelStyle());
        for (int k = 0; k < lines.size(); k++) ctx.text(mc.font, "§7" + lines.get(k), (int) p[0] + 6, (int) p[1] + 4 + k * 10, 0xFFFFFFFF, true);
    }

    @Override public String getInfo() { return found.isEmpty() ? null : String.valueOf(found.size()); }
}
