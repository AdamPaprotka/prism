package dev.prismglass.module.client;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Rejoins the last server after a kick or lost connection (2b2t queue, restarts). Adds a "Reconnect" button and a
 * countdown toggle to the disconnect screen. Never fires after Prism itself logged you out (AutoLog, StaffWatcher).
 */
public class AutoReconnect extends Module {
    public final NumberSetting delay = num("Delay", 5, 0.5, 120, 0.5, "Seconds on the disconnect screen before rejoining.");
    public final NumberSetting maxTries = num("MaxTries", 0, 0, 100, 1, "Give up after this many failed rejoins in a row (0 = never).");
    public final BoolSetting skipBans = bool("SkipBans", true, "Don't rejoin when the kick message says you're banned.");
    public final BoolSetting button = bool("Buttons", true, "Show Reconnect / countdown buttons on the disconnect screen (even while off).");

    /** Last server you connected to (recorded at ConnectScreen.startConnecting). */
    private static ServerData lastServer;
    private static String lastAddress;
    private static boolean blockNext;
    private int tries;
    private int ticksLeft = -1;
    private Button countdownButton;

    public AutoReconnect() {
        super("AutoReconnect", "Rejoins the server after a kick or a lost connection.", Category.CLIENT);
        drawn.set(false);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof DisconnectedScreen ds) onDisconnectScreen(ds);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> tries = 0);
    }

    /** ConnectScreenMixin: every connection attempt (server list, direct connect, Prism relogs). */
    public static void onConnect(ServerAddress address, ServerData data) {
        if (data == null) return;
        lastServer = data;
        lastAddress = address.getHost() + (address.getPort() == 25565 ? "" : ":" + address.getPort());
    }

    /** Prism is about to disconnect on purpose (AutoLog, StaffWatcher): don't rejoin this time. */
    public static void blockOnce() { blockNext = true; }

    public static boolean hasLastServer() { return lastServer != null; }

    /** Connects to the last server now. @return false when there is none. */
    public static boolean reconnect() {
        if (lastServer == null || lastAddress == null) return false;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level != null) mc.disconnectWithProgressScreen();
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(lastAddress), lastServer, false, null);
        });
        return true;
    }

    private void onDisconnectScreen(DisconnectedScreen screen) {
        countdownButton = null;
        // a resize re-inits the same screen: keep its countdown and reasons instead of starting over
        if (screen != countdownScreen) {
            countdownScreen = screen;
            wasBlocked = blockNext;
            blockNext = false;
            String reason = screen.getNarrationMessage().getString().toLowerCase(Locale.ROOT);
            wasBanned = skipBans.get() && (reason.contains("banned") || reason.contains("ban ") || reason.contains("blacklist"));
            wasOut = maxTries.get() > 0 && tries >= maxTries.get();
            ticksLeft = lastServer != null && isEnabled() && !wasBlocked && !wasBanned && !wasOut ? (int) Math.round(delay.get() * 20) : -1;
        }
        if (lastServer == null || !button.get() && !isEnabled()) return;
        boolean blocked = wasBlocked, banned = wasBanned, outOfTries = wasOut;

        if (button.get()) {
            int bottom = 0;
            for (AbstractWidget wd : Screens.getWidgets(screen)) bottom = Math.max(bottom, wd.getBottom());
            int x = screen.width / 2 - 100, y = Math.min(bottom + 4, screen.height - 46);
            Screens.getWidgets(screen).add(Button.builder(Component.literal("Reconnect to " + lastAddress), b -> { ticksLeft = -1; tries++; reconnect(); })
                .bounds(x, y, 200, 20).build());
            countdownButton = Button.builder(countdownText(blocked, banned, outOfTries), b -> {
                if (ticksLeft >= 0) ticksLeft = -1; // cancel this countdown
                else { setEnabled(true); ticksLeft = (int) Math.round(delay.get() * 20); }
                b.setMessage(countdownText(false, false, false));
            }).bounds(x, y + 22, 200, 20).build();
            Screens.getWidgets(screen).add(countdownButton);
        }
        ScreenEvents.afterTick(screen).register(s -> {
            if (ticksLeft < 0 || s != countdownScreen) return;
            if (!isEnabled()) { ticksLeft = -1; if (countdownButton != null) countdownButton.setMessage(countdownText(false, false, false)); return; }
            if (--ticksLeft <= 0) {
                ticksLeft = -1;
                tries++;
                reconnect();
                return;
            }
            if (countdownButton != null) countdownButton.setMessage(countdownText(false, false, false));
        });
    }

    private DisconnectedScreen countdownScreen;
    private boolean wasBlocked, wasBanned, wasOut;

    private Component countdownText(boolean blocked, boolean banned, boolean outOfTries) {
        if (ticksLeft >= 0) return Component.literal(String.format(Locale.ROOT, "AutoReconnect in %.1fs  §7(click to cancel)", ticksLeft / 20.0));
        String why = blocked ? " §7(Prism logged you out)" : banned ? " §7(ban message)" : outOfTries ? " §7(" + tries + " tries)" : "";
        return Component.literal("AutoReconnect: §cOff" + why + " §7- click to start");
    }

    @Override public String getInfo() { return String.format(Locale.ROOT, "%.1fs", delay.get()); }

    /** Dev tests: the countdown state on the current disconnect screen (-1 = none). */
    public int devTicksLeft() { return ticksLeft; }
    public static String devLastAddress() { return lastAddress; }
}
