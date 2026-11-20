package dev.prismglass.module.client;

import com.google.gson.JsonObject;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.manager.DiscordIpc;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.TextSetting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.InvUtil;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.world.item.Items;

/**
 * Discord Rich Presence: server, queue position (2b2t-style "Position in queue"), dimension / health / module count,
 * elapsed time. Needs your own Discord application id (discord.com/developers/applications → New Application →
 * copy the Application ID): Discord shows the application's name as the game.
 */
public class DiscordRPC extends Module {
    public final TextSetting clientId = text("ClientId", "", "Application ID of your Discord app (its name is what Discord shows you playing).");
    public final BoolSetting showServer = bool("ShowServer", true, "Show the server address (off: just \"Multiplayer\").");
    public final ModeSetting state = mode("State", "Dimension", "Second line.", "Dimension", "Health", "Modules", "Custom", "None");
    public final TextSetting custom = text("Custom", "Prism Glass on top", "Second line for State Custom.");
    public final BoolSetting queue = bool("Queue", true, "Show your queue position when the server reports one.");
    public final TextSetting image = text("Image", "https://raw.githubusercontent.com/AdamPaprotka/prism/main/src/main/resources/assets/prismglass/icon.png",
        "Large image: an asset key uploaded to your Discord app, or an https URL.");

    private static final Pattern QUEUE = Pattern.compile("(?i)position in (?:the )?queue[^0-9]{0,8}(\\d{1,6})");

    private volatile String details = "In the menus", line2 = "", serverKey = "";
    private volatile long started = System.currentTimeMillis() / 1000;
    private volatile int queuePos = -1;
    private volatile String status = "off";
    private volatile boolean dirty = true;
    private Thread worker;
    private int snapshotTicks;

    public DiscordRPC() {
        super("DiscordRPC", "Shows what you're playing on your Discord profile.", Category.CLIENT);
        drawn.set(false);
        // runs on the title screen too (module hooks only run in a world)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isEnabled()) return;
            if (worker == null || !worker.isAlive()) start(); // enabled from the saved profile before any world: no onEnable
            if (++snapshotTicks >= 20) { snapshotTicks = 0; snapshot(); }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> queuePos = -1);
    }

    @Override
    public void onEnable() { start(); }

    @Override
    public void onDisable() { /* the worker sees isEnabled() go false, clears the presence and closes */ }

    private synchronized void start() {
        if (clientId.get().isBlank()) {
            ChatUtil.error("DiscordRPC needs an app id: discord.com/developers/applications → New Application → copy Application ID → .set discordrpc clientid <id>");
            status = "no ClientId";
            setEnabled(false);
            return;
        }
        if (worker != null && worker.isAlive()) return;
        dirty = true;
        worker = new Thread(this::loop, "Prism-DiscordRPC");
        worker.setDaemon(true);
        worker.start();
    }

    /** Main thread: reads the game state into strings the worker sends. */
    private void snapshot() {
        String d, s, key;
        var server = mc.getCurrentServer();
        if (mc.level == null || mc.player == null) {
            d = "In the menus"; s = ""; key = "";
        } else {
            boolean single = mc.isLocalServer();
            key = single ? "sp" : server == null ? "mp" : server.ip;
            String where = single ? "Singleplayer" : !showServer.get() || server == null ? "Multiplayer" : "on " + server.ip.toLowerCase(Locale.ROOT);
            if (queue.get() && queuePos >= 0) d = "In queue #" + queuePos + (single || !showServer.get() || server == null ? "" : " " + where);
            else d = single ? "Playing Singleplayer" : "Playing " + where;
            s = switch (state.get()) {
                case "Dimension" -> dimension();
                case "Health" -> String.format(Locale.ROOT, "%.0f HP · %d totems", mc.player.getHealth() + mc.player.getAbsorptionAmount(), InvUtil.count(Items.TOTEM_OF_UNDYING));
                case "Modules" -> modulesOn() + " modules on";
                case "Custom" -> custom.get();
                default -> "";
            };
        }
        if (!key.equals(serverKey)) { serverKey = key; started = System.currentTimeMillis() / 1000; }
        if (!d.equals(details) || !s.equals(line2)) { details = d; line2 = s; dirty = true; }
    }

    private String dimension() {
        String id = mc.level.dimension().identifier().getPath();
        return switch (id) {
            case "overworld" -> "In the Overworld";
            case "the_nether" -> "In the Nether";
            case "the_end" -> "In the End";
            default -> "In " + id.replace('_', ' ');
        };
    }

    private int modulesOn() {
        int n = 0;
        for (Module m : Prism.modules().enabled()) if (!m.internal() && m.drawn.get()) n++;
        return n;
    }

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (!queue.get()) return;
        String text = null;
        if (event.packet instanceof ClientboundSystemChatPacket p) text = p.content().getString();
        else if (event.packet instanceof ClientboundTabListPacket p) text = p.header().getString() + "\n" + p.footer().getString();
        if (text == null) return;
        Matcher m = QUEUE.matcher(text);
        if (m.find()) {
            int pos = Integer.parseInt(m.group(1));
            if (pos != queuePos) { queuePos = pos; dirty = true; }
        } else if (event.packet instanceof ClientboundTabListPacket && queuePos >= 0 && !text.toLowerCase(Locale.ROOT).contains("queue")) {
            queuePos = -1; // out of the queue: the header stopped mentioning it
            dirty = true;
        }
    }

    // ---- worker -------------------------------------------------------------------------------------------

    private void loop() {
        DiscordIpc ipc = new DiscordIpc();
        long nextConnect = 0, lastSend = 0;
        String lastError = null;
        try {
            while (isEnabled()) {
                long now = System.currentTimeMillis();
                if (!ipc.connected() && now >= nextConnect) {
                    try {
                        ipc.connect(clientId.get().trim());
                        status = "connected" + (ipc.user() == null ? "" : " as " + ipc.user());
                        dirty = true;
                        lastError = null;
                    } catch (Exception e) {
                        status = e.getMessage();
                        if (!String.valueOf(e.getMessage()).equals(lastError)) Prism.LOG.info("DiscordRPC: {}", e.getMessage());
                        lastError = e.getMessage();
                        nextConnect = now + 15_000;
                    }
                }
                // Discord allows 5 presence updates per 20 s
                if (ipc.connected() && dirty && now - lastSend >= 4_000) {
                    dirty = false;
                    lastSend = now;
                    try { ipc.setActivity(activity()); }
                    catch (Exception e) { status = e.getMessage(); Prism.LOG.info("DiscordRPC: {}", e.getMessage()); nextConnect = now + 15_000; dirty = true; }
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (ipc.connected()) try { ipc.setActivity(null); } catch (Exception ignored) {}
            ipc.close();
            status = "off";
        }
    }

    private JsonObject activity() {
        JsonObject a = new JsonObject();
        a.addProperty("details", clamp(details));
        if (!line2.isEmpty()) a.addProperty("state", clamp(line2));
        JsonObject ts = new JsonObject();
        ts.addProperty("start", started);
        a.add("timestamps", ts);
        JsonObject assets = new JsonObject();
        if (!image.get().isBlank()) assets.addProperty("large_image", image.get().trim());
        assets.addProperty("large_text", "Prism Glass " + Prism.VERSION);
        a.add("assets", assets);
        return a;
    }

    /** Discord rejects details/state outside 2..128 characters. */
    private static String clamp(String s) {
        if (s.length() < 2) s = s + "  ";
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    @Override public String getInfo() { return status; }

    public String status() { return status; }
}
