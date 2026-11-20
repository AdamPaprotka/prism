package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.api.Addons;
import dev.prismglass.api.PrismAddon;
import dev.prismglass.api.PrismApi;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.manager.DiscordIpc;
import dev.prismglass.manager.Macros;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.AutoReconnect;
import dev.prismglass.module.client.DiscordRPC;
import dev.prismglass.module.client.KeyActions;
import dev.prismglass.module.render.Fullbright;
import dev.prismglass.setting.NumberSetting;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev only (-Paddontest): addon API (module + command + completion + profile save), macros (key press, !wait,
 * server command, save/load, Keybinds row), DiscordRPC (queue parse, real IPC handshake refused for a fake app id),
 * AutoReconnect (buttons, countdown, real reconnect attempt to a closed port, blockOnce, ban message).
 */
public final class AddonTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t, phase, phaseStart, pass, fail, startKey;
    private static boolean requested;
    private static int addonTicks;
    private static String addonCmdArg;

    private AddonTest() {}

    /** A test addon, loaded through the same path as a real "prismglass" entrypoint. */
    public static final class TestAddonModule extends Module {
        public final NumberSetting power = num("Power", 3, 0, 10, 1, "Test setting.");
        public TestAddonModule() { super("TestAddonMod", "Module from the dev test addon.", Category.WORLD); }
        @Override public void onTick() { addonTicks++; }
    }

    public static void registerTestAddon() {
        Addons.load(new PrismAddon() {
            @Override public void onInitialize(PrismApi api) {
                api.registerModule(new TestAddonModule());
                api.registerCommand("addonping", "addonping <word>", "Test addon command.", a -> addonCmdArg = a.length > 0 ? a[0] : "",
                    a -> a.length == 0 ? List.of("pong", "ping") : List.of());
            }
        }, "Test Addon", "prismglass-testaddon", "0.0.1");
    }

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void check(String what, boolean ok, Object detail) {
        if (ok) pass++; else fail++;
        Prism.LOG.info("[addontest] {} {} ({})", ok ? "PASS" : "FAIL", what, detail);
    }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void next() { phase++; phaseStart = ticks; }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        try {
            if (phase >= 10) { reconnectPhases(); return; }
            if (!requested && ticks == 80 && mc.level == null) {
                requested = true;
                String name = "prismaddon-" + System.currentTimeMillis();
                LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                    lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
                return;
            }
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
                || mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
            t++;
            MinecraftServer s = mc.getSingleplayerServer();
            if (t == 20) {
                for (var m : Prism.modules().all())
                    if (m.isEnabled() && !m.internal() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
                run(s, "gamerule advance_time false");
                if (mc.gui.screen() != null) mc.gui.setScreen(null);
            }
            if (t < 40) return;
            switch (phase) {
                case 0 -> { addon(); next(); }
                case 1 -> { if (ticks - phaseStart >= 10) { check("addon module ticks while on", addonTicks >= 8, addonTicks + " ticks"); Prism.modules().get(TestAddonModule.class).setEnabled(false); next(); } }
                case 2 -> { macroStart(s); next(); }
                case 3 -> macroWait(s);
                case 4 -> { discord(); next(); }
                case 5 -> {
                    // leave the world for the AutoReconnect checks
                    mc.level.disconnect(Component.literal("addontest"));
                    mc.disconnectWithProgressScreen();
                    phase = 10;
                    phaseStart = ticks;
                }
                default -> {}
            }
        } catch (Throwable e) {
            check("no exception in phase " + phase, false, e);
            Prism.LOG.error("[addontest]", e);
            finish();
        }
    }

    // ---- addon API ----------------------------------------------------------------------------------------

    private static void addon() throws Exception {
        var info = Addons.loaded().stream().filter(a -> a.modId().equals("prismglass-testaddon")).findFirst();
        check("addon listed as loaded", info.isPresent() && info.get().ok(), info);
        Module m = Prism.modules().get("TestAddonMod");
        check("addon module registered (found by name)", m instanceof TestAddonModule, m);
        Prism.commands().handle(".addonping hello");
        check("addon command runs", "hello".equals(addonCmdArg), addonCmdArg);
        var comp = Prism.commands().complete(".addonping p");
        check("addon command tab completion", comp != null && comp.options().contains("pong") && comp.options().contains("ping"), comp == null ? null : comp.options());
        boolean dupRefused = false;
        try { Prism.commands().register("toggle", "x", "x", a -> {}, null); } catch (IllegalArgumentException e) { dupRefused = true; }
        check("duplicate command name refused", dupRefused, "");
        Prism.commands().handle(".set testaddonmod power 7");
        Prism.commands().handle(".t testaddonmod");
        check("addon module toggles + settings via commands", m.isEnabled() && ((TestAddonModule) m).power.get() == 7, m.isEnabled());
        Prism.config().save();
        String json = Files.readString(Prism.config().folder().resolve("profiles").resolve(Prism.config().getActive() + ".json"));
        check("addon module saved in the profile", json.contains("TestAddonMod"), "");
        addonTicks = 0;
    }

    // ---- macros -------------------------------------------------------------------------------------------

    private static void macroStart(MinecraftServer s) {
        Macros mm = Prism.macros();
        mm.remove("addontest");
        Prism.commands().handle(".macro set addontest .t fullbright; !wait 5; .t fullbright; /tag @s add prismmacro; hello from a macro");
        var m = mm.get("addontest");
        check("macro created with 5 lines", m != null && m.lines.size() == 5, m == null ? null : m.lines);
        Prism.commands().handle(".macro bind addontest f8");
        var keybindsRow = Prism.modules().get(KeyActions.class).actions().stream().anyMatch(a -> a.name().equals("Macro: addontest"));
        check("macro shows in ClickGUI Keybinds", keybindsRow, "");
        // save/load round trip through a fresh manager
        mm.save();
        Macros fresh = new Macros();
        fresh.load();
        var fm = fresh.get("addontest");
        check("macro save/load keeps lines and key", fm != null && fm.lines.equals(m.lines) && fm.key.get().equals(m.key.get()), fm == null ? null : fm.lines);
        Macros.DEV_SENT.clear();
        Macros.devDryRun = false;
        startKey = m.key.get();
        Prism.modules().onKey(startKey, true); // the real key path: ModuleManager -> KeyActions -> macro
        Prism.modules().onKey(startKey, false);
        check("first line runs on the key press", Prism.modules().get(Fullbright.class).isEnabled(), "Fullbright on");
    }

    private static void macroWait(MinecraftServer s) {
        int dt = ticks - phaseStart;
        boolean fb = Prism.modules().get(Fullbright.class).isEnabled();
        if (dt == 3) check("!wait holds the next line", fb, "still on at +3 ticks");
        if (dt == 15) {
            check("line after !wait ran", !fb, "Fullbright off");
            var sp = s.getPlayerList().getPlayers().get(0);
            check("/command line reached the server", sp.entityTags().contains("prismmacro"), sp.entityTags());
            check("macro finished", !Prism.macros().running(), "");
            Prism.macros().remove("addontest");
            Prism.macros().save();
            next();
        }
    }

    // ---- Discord RPC --------------------------------------------------------------------------------------

    private static void discord() {
        DiscordRPC rpc = Prism.modules().get(DiscordRPC.class);
        rpc.queue.set(true);
        rpc.setEnabledSilently(true); // packet hook needs it on; no ClientId -> worker refuses to start
        rpc.onPacketReceive(new PacketEvent(new ClientboundSystemChatPacket(Component.literal("Position in queue: 412"), false)));
        int q = devQueue(rpc);
        check("queue position parsed from chat", q == 412, q);
        rpc.setEnabledSilently(false);
        // real Discord IPC: handshake with a fake app id must reach Discord and be refused (not hang, not crash)
        DiscordIpc ipc = new DiscordIpc();
        String result;
        try { ipc.connect("1"); result = "accepted?!"; ipc.setActivity(null); }
        catch (Exception e) { result = e.getMessage(); }
        finally { ipc.close(); }
        boolean discordUp = !String.valueOf(result).contains("isn't running");
        Prism.LOG.info("[addontest] Discord IPC answer: {}", result);
        check("Discord IPC round trip (refused fake app id)" + (discordUp ? "" : " - Discord not running, transport only"),
            String.valueOf(result).toLowerCase().contains("client") || String.valueOf(result).contains("4000") || !discordUp, result);
    }

    private static int devQueue(DiscordRPC rpc) {
        try {
            var f = DiscordRPC.class.getDeclaredField("queuePos");
            f.setAccessible(true);
            return f.getInt(rpc);
        } catch (Exception e) { return -2; }
    }

    // ---- AutoReconnect ------------------------------------------------------------------------------------

    private static int connectScreensSeen, disconnectScreensSeen;
    private static Object lastSeenScreen;

    private static void reconnectPhases() {
        var ar = Prism.modules().get(AutoReconnect.class);
        int dt = ticks - phaseStart;
        var screen = mc.gui.screen();
        if (screen != lastSeenScreen) {
            lastSeenScreen = screen;
            if (screen instanceof ConnectScreen) connectScreensSeen++;
            if (screen instanceof DisconnectedScreen) disconnectScreensSeen++;
        }
        switch (phase) {
            case 10 -> {
                if (mc.level != null || dt < 40) return;
                ar.delay.set(1.0);
                ar.maxTries.set(0.0);
                ar.setEnabledSilently(true);
                // remember a server nobody listens on, as if we had joined it
                AutoReconnect.onConnect(ServerAddress.parseString("127.0.0.1:25598"), new ServerData("addontest", "127.0.0.1:25598", ServerData.Type.OTHER));
                mc.gui.setScreen(new DisconnectedScreen(new TitleScreen(), Component.literal("Connection Lost"), Component.literal("Server closed")));
                next();
            }
            case 11 -> {
                if (dt == 2) {
                    var labels = Screens.getWidgets(mc.gui.screen()).stream().map(w -> w.getMessage().getString()).toList();
                    check("Reconnect button added", labels.stream().anyMatch(l -> l.startsWith("Reconnect to 127.0.0.1:25598")), labels);
                    check("countdown running", ar.devTicksLeft() > 0 && labels.stream().anyMatch(l -> l.startsWith("AutoReconnect in")), ar.devTicksLeft());
                    connectScreensSeen = 0;
                    disconnectScreensSeen = 0;
                }
                // 1 s countdown -> ConnectScreen -> refused -> new DisconnectedScreen (which counts down again)
                if (dt == 200) {
                    check("countdown reconnected (ConnectScreen shown)", connectScreensSeen >= 1, connectScreensSeen + " connect screens");
                    check("failed reconnect lands on a new disconnect screen", disconnectScreensSeen >= 1, disconnectScreensSeen);
                    check("mixin recorded the reconnect target", "127.0.0.1:25598".equals(AutoReconnect.devLastAddress()), AutoReconnect.devLastAddress());
                    ar.setEnabledSilently(false);
                    next();
                }
            }
            case 12 -> {
                if (dt < 10) return;
                ar.setEnabledSilently(true);
                AutoReconnect.blockOnce();
                mc.gui.setScreen(new DisconnectedScreen(new TitleScreen(), Component.literal("Disconnected"), Component.literal("[Prism AutoLog] low health")));
                check("no countdown after Prism's own logout", ar.devTicksLeft() < 0, ar.devTicksLeft());
                mc.gui.setScreen(new DisconnectedScreen(new TitleScreen(), Component.literal("Disconnected"), Component.literal("You are banned from this server")));
                check("no countdown on a ban message", ar.devTicksLeft() < 0, ar.devTicksLeft());
                mc.gui.setScreen(new DisconnectedScreen(new TitleScreen(), Component.literal("Disconnected"), Component.literal("Server restarting")));
                check("countdown again on a normal kick", ar.devTicksLeft() > 0, ar.devTicksLeft());
                ar.setEnabledSilently(false);
                mc.gui.setScreen(new TitleScreen());
                finish();
            }
            default -> {}
        }
    }

    private static void finish() {
        var tm = Prism.modules().get(TestAddonModule.class);
        if (tm != null) tm.setEnabled(false);
        Prism.config().save();
        Prism.LOG.info("[addontest] DONE {} passed, {} failed", pass, fail);
        Runtime.getRuntime().halt(0);
    }
}
