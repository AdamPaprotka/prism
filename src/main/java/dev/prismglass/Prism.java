package dev.prismglass;

import dev.prismglass.command.CommandManager;
import dev.prismglass.config.ConfigManager;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.manager.FriendManager;
import dev.prismglass.manager.PacketGuard;
import dev.prismglass.manager.RotationManager;
import dev.prismglass.module.ModuleManager;
import dev.prismglass.module.ModuleRegistry;
import dev.prismglass.module.client.AntiCheat;
import dev.prismglass.util.BlockUtil;
import dev.prismglass.util.Render3D;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Prism implements ClientModInitializer {
    public static final String NAME = "Prism";
    public static final String VERSION = "1.6.3";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    private static ModuleManager modules;
    private static RotationManager rotations;
    private static PacketGuard guard;
    private static FriendManager friends;
    private static CommandManager commands;
    private static ConfigManager config;
    private static AntiCheat anticheat;
    private static dev.prismglass.manager.ReachBudget reachBudget;
    private static dev.prismglass.manager.InventoryGuard invGuard;
    private static dev.prismglass.manager.Humanizer humanizer;
    private static dev.prismglass.manager.FakePlayers fakePlayers;
    private static dev.prismglass.manager.AccountManager accounts;
    private static dev.prismglass.manager.ProxyManager proxies;
    private static dev.prismglass.manager.BotPlayers bots;

    private static int autosaveTicks;
    private static final boolean COMBAT_TEST = Boolean.getBoolean("prismglass.combattest");
    private static final boolean BEHIND_TEST = Boolean.getBoolean("prismglass.behindtest");

    @Override
    public void onInitializeClient() {
        long start = System.currentTimeMillis();
        modules = new ModuleManager();
        rotations = new RotationManager();
        guard = new PacketGuard();
        friends = new FriendManager();
        commands = new CommandManager();
        config = new ConfigManager();
        reachBudget = new dev.prismglass.manager.ReachBudget();
        invGuard = new dev.prismglass.manager.InventoryGuard();
        humanizer = new dev.prismglass.manager.Humanizer();
        fakePlayers = new dev.prismglass.manager.FakePlayers();
        accounts = new dev.prismglass.manager.AccountManager();
        proxies = new dev.prismglass.manager.ProxyManager();
        bots = new dev.prismglass.manager.BotPlayers();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> bots.tick());

        ModuleRegistry.registerAll(modules);
        anticheat = modules.get(AntiCheat.class);
        config.load();
        anticheat.markLoaded();

        // HUD is drawn from GameRendererMixin as the topmost GUI layer (see renderHud)
        LevelRenderEvents.END_MAIN.register(Prism::onRenderWorld);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            reachBudget.reset();
            modules.onWorldJoin();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            guard.onDisconnect();
            var spike = modules.get(dev.prismglass.module.movement.PingSpike.class);
            if (spike != null) spike.abort("disconnected");
            fakePlayers.clear();
            invGuard.reset();
            config.save();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { config.save(); bots.leaveAll(); proxies.tor().stop(); });

        LOG.info("{} {} loaded {} modules in {}ms", NAME, VERSION, modules.all().size(), System.currentTimeMillis() - start);

        // Dev check: -Dprismglass.guitest=true opens the ClickGUI on the title screen, screenshots it
        // to run/screenshots, then exits. Lets the GUI be verified without joining a world.
        // Dev check: -Dprismglass.guiblocky=true screenshots the Blocky ClickGUI in each style, then exits
        if (Boolean.getBoolean("prismglass.guiblocky")) {
            int[] ticks = {0};
            String[] styles = {"Liquid", "Frosted", "Flat"};
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                var theme = modules.get(dev.prismglass.module.client.ClickGui.class);
                int t = ++ticks[0];
                if (t == 100) { theme.blocky.set(true); theme.style.parse(styles[0]); client.setScreen(new dev.prismglass.gui.ClickGuiScreen()); }
                for (int i = 0; i < styles.length; i++) {
                    if (t == 100 + i * 50) theme.style.parse(styles[i]);
                    if (t == 140 + i * 50) net.minecraft.client.Screenshot.grab(client.gameDirectory, "prism-blocky-" + styles[i].toLowerCase() + ".png",
                        client.getMainRenderTarget(), 1, msg -> LOG.info("[guiblocky] {}", msg.getString()));
                }
                if (t == 100 + styles.length * 50) {
                    theme.blocky.set(false);
                    theme.style.parse("Liquid");
                    config.save();
                    LOG.info("[guiblocky] done");
                    Runtime.getRuntime().halt(0);
                }
            });
        }
        if (Boolean.getBoolean("prismglass.guitest")) {
            int[] ticks = {0};
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                ticks[0]++;
                if (ticks[0] == 120) client.setScreen(new dev.prismglass.gui.ClickGuiScreen());
                if (ticks[0] == 180) {
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "prism-guitest.png",
                        client.getMainRenderTarget(), 1, msg -> LOG.info("[guitest] {}", msg.getString()));
                }
                if (client.screen instanceof dev.prismglass.gui.ClickGuiScreen gui) {
                    // right-click a slider -> type a number (Enter applies, clamped, not step-snapped; Esc cancels)
                    if (ticks[0] == 150) {
                        // the first module shown in a panel that has a slider
                        for (var mod : modules.all()) {
                            if (gui.debugRect(mod) == null) continue;
                            var num = mod.getSettings().stream().filter(st -> st instanceof dev.prismglass.setting.NumberSetting).findFirst();
                            if (num.isPresent()) { guiTestMod = mod; guiTestNum = (dev.prismglass.setting.NumberSetting) num.get(); break; }
                        }
                        LOG.info("[guitest] slider test on {} / {}", guiTestMod == null ? "-" : guiTestMod.getName(), guiTestNum == null ? "-" : guiTestNum.getName());
                    }
                    var theme = guiTestMod;
                    var ns = guiTestNum;
                    java.util.function.BiConsumer<float[], Integer> press = (r, b) -> gui.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                        r[0] + r[2] * 0.5, r[1] + r[3] * 0.5, new net.minecraft.client.input.MouseButtonInfo(b, 0)), false);
                    java.util.function.Consumer<String> type = str -> { for (char c : str.toCharArray()) gui.charTyped(new net.minecraft.client.input.CharacterEvent(c)); };
                    if (ticks[0] == 150 && theme != null) { float[] r = gui.debugRect(theme); if (r != null) press.accept(r, 1); } // expand its settings
                    if (ticks[0] == 156 && ns != null) {
                        float[] r = gui.debugRect(ns);
                        if (r == null) LOG.info("[guitest] FAIL slider AnimSpeed not visible");
                        else {
                            press.accept(r, 1);
                            String shown = gui.debugNumInput();
                            double target = ns.getMin() + (ns.getMax() - ns.getMin()) * 0.37 + ns.getStep() * 0.31; // off the step grid
                            String typed = String.format(java.util.Locale.ROOT, "%.3f", target).replace('.', ',');
                            type.accept(typed);
                            gui.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
                            double want = Double.parseDouble(typed.replace(',', '.'));
                            LOG.info("[guitest] {} right-click opens the number box (showed '{}'), typed {} -> {} (display {})",
                                shown != null && Math.abs(ns.get() - want) < 1e-9 ? "PASS" : "FAIL", shown, typed, ns.get(), ns.display());
                        }
                    }
                    if (ticks[0] == 157 && ns != null) { gui.debugTypeNumber(ns); type.accept("12,5"); }
                    if (ticks[0] == 159 && ns != null)
                        net.minecraft.client.Screenshot.grab(client.gameDirectory, "prism-guitest-number.png", client.getMainRenderTarget(), 1, msg -> LOG.info("[guitest] {}", msg.getString()));
                    if (ticks[0] == 160 && ns != null) {
                        gui.debugTypeNumber(ns);
                        type.accept("99");
                        gui.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
                        LOG.info("[guitest] {} 99 clamps to the max -> {}", ns.get() == ns.getMax() ? "PASS" : "FAIL", ns.get());
                        double before = ns.get();
                        gui.debugTypeNumber(ns);
                        type.accept("1");
                        gui.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
                        LOG.info("[guitest] {} Esc cancels (still {}), GUI still open: {}", ns.get() == before && client.screen == gui ? "PASS" : "FAIL", ns.get(), client.screen == gui);
                        float[] r = gui.debugRect(ns);
                        if (r != null) { press.accept(r, 2); LOG.info("[guitest] {} middle-click resets -> {}", ns.get().equals(ns.getDefault()) ? "PASS" : "FAIL", ns.get()); }
                        ns.reset();
                        float[] m = gui.debugRect(theme);
                        if (m != null) press.accept(m, 1); // collapse again for the screenshots
                    }
                    if (ticks[0] == 170) {
                        // a bind pressed in the GUI toggles its module and must not type into the search
                        var m = modules.get(dev.prismglass.module.render.Fullbright.class);
                        int k = org.lwjgl.glfw.GLFW.GLFW_KEY_K;
                        m.bind.set(k);
                        boolean before = m.isEnabled();
                        gui.keyPressed(new net.minecraft.client.input.KeyEvent(k, 0, 0));
                        gui.charTyped(new net.minecraft.client.input.CharacterEvent('k'));
                        gui.keyReleased(new net.minecraft.client.input.KeyEvent(k, 0, 0));
                        LOG.info("[guitest] bind in GUI: toggled={} searchEmpty={}", m.isEnabled() != before, gui.debugSearch().isEmpty());
                        m.setEnabled(before);
                        m.bind.set(dev.prismglass.util.KeyUtil.NONE);
                    }
                    if (ticks[0] == 190) gui.debugMenu(1);
                    if (ticks[0] == 250) gui.debugMenu(2);
                }
                if (ticks[0] == 230 || ticks[0] == 290) {
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, ticks[0] == 230 ? "prism-guitest-config.png" : "prism-guitest-profiles.png",
                        client.getMainRenderTarget(), 1, msg -> LOG.info("[guitest] {}", msg.getString()));
                }
                if (client.screen instanceof dev.prismglass.gui.ClickGuiScreen gui) {
                    if (ticks[0] == 295) { gui.debugMenu(0); var x = modules.get(dev.prismglass.module.render.Xray.class); gui.debugList(x, x.blocks, "dia"); }
                    if (ticks[0] == 345) { var im = modules.get(dev.prismglass.module.player.InventoryManager.class); gui.debugList(im, im.layout, ""); }
                }
                if (ticks[0] == 335 || ticks[0] == 385) {
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, ticks[0] == 335 ? "prism-guitest-list.png" : "prism-guitest-layout.png",
                        client.getMainRenderTarget(), 1, msg -> LOG.info("[guitest] {}", msg.getString()));
                }
                if (ticks[0] == 400) { LOG.info("[guitest] done"); Runtime.getRuntime().halt(0); }
            });
        }

        // Dev check: -Dprismglass.combattest=true runs an automated KillAura/AutoWalk test in a flat world
        if (Boolean.getBoolean("prismglass.combattest")) dev.prismglass.dev.CombatTest.install();
        // Dev check: -Dprismglass.behindtest=true runs the AutoWalk Behind test (turning dummy, statue, real zombie)
        if (BEHIND_TEST) dev.prismglass.dev.BehindTest.install();
        // Dev check: -Dprismglass.inspecttest=true checks every Inspect clip and screenshots them
        if (Boolean.getBoolean("prismglass.inspecttest")) dev.prismglass.dev.InspectTest.install();
        // Dev check: -Dprismglass.modtest=true runs one scripted case per spec module (ModCases)
        if (Boolean.getBoolean("prismglass.modtest")) dev.prismglass.dev.ModTest.install();
        // Dev check: -Dprismglass.xraytest=true measures the seed ore simulator against a real world
        if (Boolean.getBoolean("prismglass.xraytest")) dev.prismglass.dev.XrayTest.install();
        // Dev check: -Dprismglass.streamtest=true checks StreamProof (capture exclusion, overlay, borderless)
        if (Boolean.getBoolean("prismglass.streamtest")) dev.prismglass.dev.StreamTest.install();
        if (Boolean.getBoolean("prismglass.fstest")) dev.prismglass.dev.FullscreenTest.install();
        if (Boolean.getBoolean("prismglass.structtest")) dev.prismglass.dev.StructureTest.install();
        if (Boolean.getBoolean("prismglass.elytratest")) dev.prismglass.dev.ElytraTest.install();
        if (Boolean.getBoolean("prismglass.flighttest")) dev.prismglass.dev.FlightTest.install();
        if (Boolean.getBoolean("prismglass.hudtest")) dev.prismglass.dev.HudTest.install();
        if (Boolean.getBoolean("prismglass.pluginstest")) dev.prismglass.dev.PluginsTest.install();
        if (Boolean.getBoolean("prismglass.proxytest")) dev.prismglass.dev.ProxyTest.install();
        if (Boolean.getBoolean("prismglass.bottest")) dev.prismglass.dev.BotTest.install();
        if (Boolean.getBoolean("prismglass.pearltest")) dev.prismglass.dev.PearlTest.install();
        if (Boolean.getBoolean("prismglass.minetest")) dev.prismglass.dev.MineTest.install();
        if (Boolean.getBoolean("prismglass.dupetest")) dev.prismglass.dev.DupeTest.install();
        if (Boolean.getBoolean("prismglass.stafftest")) dev.prismglass.dev.StaffTest.install();
        if (Boolean.getBoolean("prismglass.holotest")) dev.prismglass.dev.HoloTest.install();
        if (Boolean.getBoolean("prismglass.sustest")) dev.prismglass.dev.SusTest.install();
        if (Boolean.getBoolean("prismglass.rebreaktest")) dev.prismglass.dev.RebreakTest.install();
        if (Boolean.getBoolean("prismglass.pingspiketest")) dev.prismglass.dev.PingSpikeTest.install();
        if (Boolean.getBoolean("prismglass.pflytest")) dev.prismglass.dev.PacketFlyTest.install();
        if (Boolean.getBoolean("prismglass.crystaltest")) dev.prismglass.dev.CrystalTest.install();
        if (Boolean.getBoolean("prismglass.elytrabottest")) dev.prismglass.dev.ElytraBotTest.install();
        if (Boolean.getBoolean("prismglass.grimtest")) dev.prismglass.dev.GrimTest.install();

        // Dev check: -Dprismglass.audit=true force-applies every mixin at startup and exits,
        // so broken injection targets show up without having to join a world.
        if (Boolean.getBoolean("prismglass.audit")) {
            try {
                org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
                LOG.info("[audit] all mixins applied OK");
            } catch (Throwable t) {
                LOG.error("[audit] mixin audit FAILED", t);
            }
            Runtime.getRuntime().halt(0);
        }
    }

    // ---- global hooks (called from mixins) ------------------------------------------------

    /**
     * Prism's HUD in its own stratum on top of everything vanilla draws: HUD, chat, screens, tooltips,
     * toasts, F3. The ClickGUI is the one exception; there the HUD goes underneath so panels stay usable.
     */
    public static void renderHud(net.minecraft.client.gui.GuiGraphicsExtractor ctx, float delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || modules == null || mc.screen instanceof dev.prismglass.gui.NowPlayingScreen) return; // leaving/respawning: no world for a frame
        ctx.nextStratum();
        dev.prismglass.streamproof.Overlay.markGui();
        modules.onRender2D(ctx, delta);
    }

    /** Start of MinecraftClient#tick: before input handling, player tick and movement packets. */
    private static boolean updateChecked;

    /** Once per version: "Prism updated" toast pointing at the changelog. */
    private static void announceUpdate() {
        if (updateChecked) return;
        updateChecked = true;
        dev.prismglass.manager.Updater.check(true); // newer release on GitHub? toast + Update button
        var theme = modules.get(dev.prismglass.module.client.ClickGui.class);
        String latest = dev.prismglass.gui.ChangelogScreen.latestVersion();
        if (latest == null || latest.equals(theme.seenVersion.get())) return;
        theme.seenVersion.set(latest);
        dev.prismglass.module.client.Hud.message("Prism updated to " + latest + "  §7-  .changelog or Config > Changelog", 0xFFB8D4FF);
        config.save();
    }

    public static void onTickStart() {
        Minecraft mc = Minecraft.getInstance();
        anticheat.update();
        if (mc.player == null || mc.level == null || mc.isPaused()) return;
        guard.onTickStart();
        invGuard.onTick();
        announceUpdate();
        BlockUtil.resetBudget();
        modules.onTick();
        rotations.update();
        if (++autosaveTicks >= 20 * 120) {
            autosaveTicks = 0;
            config.save();
        }
    }

    /** End of the main level pass (26.1: Fabric LevelRenderEvents). */
    public static void onRenderWorld(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        float delta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Render3D.begin(ctx);
        modules.onRender3D(ctx.poseStack(), delta);
        dev.prismglass.gui.render.PlayerHat.render(delta);
        if (dev.prismglass.gui.render.Holo.active()) dev.prismglass.gui.render.Holo.renderWorld(ctx);
        Render3D.end();
    }

    /** Every outgoing packet. Returns true to cancel. */
    public static boolean onPacketSend(net.minecraft.network.protocol.Packet<?> packet) {
        if (Minecraft.getInstance().player == null) return false;
        dev.prismglass.module.movement.PingSpike spike = modules.get(dev.prismglass.module.movement.PingSpike.class);
        if (spike != null && spike.flushing()) return false; // held packets being released: already went through the hooks
        if (COMBAT_TEST) dev.prismglass.dev.CombatTest.onSend(packet);
        if (BEHIND_TEST) dev.prismglass.dev.BehindTest.onSend(packet);
        PacketEvent event = new PacketEvent(packet);
        modules.onPacketSend(event);
        if (event.isCancelled()) return true;
        guard.onSend(event);
        if (event.isCancelled()) return true;
        if (packet instanceof ServerboundMovePlayerPacket move) rotations.onSend(move);
        return spike != null && spike.holdOutgoing(packet);
    }

    /** Every incoming packet (netty thread). Returns true to cancel. */
    /** Server position corrections received (teleports and anticheat setbacks); dev tests diff it. */
    public static volatile int serverTeleports;

    public static boolean onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        if (Minecraft.getInstance().player == null) return false;
        dev.prismglass.module.movement.PingSpike spike = modules.get(dev.prismglass.module.movement.PingSpike.class);
        if (spike != null && spike.holdIncoming(packet)) return true; // replayed through here on release
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) serverTeleports++;
        PacketEvent event = new PacketEvent(packet);
        modules.onPacketReceive(event);
        return event.isCancelled();
    }

    // ---- accessors ---------------------------------------------------------------------------

    public static ModuleManager modules() { return modules; }
    public static RotationManager rotations() { return rotations; }
    public static PacketGuard guard() { return guard; }
    public static FriendManager friends() { return friends; }
    public static CommandManager commands() { return commands; }

    // guitest: the module/slider used for the number-box check
    private static dev.prismglass.module.Module guiTestMod;
    private static dev.prismglass.setting.NumberSetting guiTestNum;
    public static ConfigManager config() { return config; }
    public static AntiCheat anticheat() { return anticheat; }
    public static dev.prismglass.manager.ReachBudget reachBudget() { return reachBudget; }
    public static dev.prismglass.manager.InventoryGuard invGuard() { return invGuard; }
    public static dev.prismglass.manager.Humanizer humanizer() { return humanizer; }
    public static dev.prismglass.manager.FakePlayers fakePlayers() { return fakePlayers; }
    public static dev.prismglass.manager.AccountManager accounts() { return accounts; }
    public static dev.prismglass.manager.ProxyManager proxies() { return proxies; }
    public static dev.prismglass.manager.BotPlayers bots() { return bots; }
}
