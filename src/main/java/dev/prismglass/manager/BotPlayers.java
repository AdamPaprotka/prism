package dev.prismglass.manager;

import dev.prismglass.Prism;
import dev.prismglass.util.ChatUtil;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import com.mojang.authlib.GameProfile;
import java.time.Duration;
import java.util.HashMap;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.ServerLinks;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;

/**
 * Real second players (.joinplayer / .leaveplayer / .sayplayer): each bot is its own connection to the server you
 * are on, logged in as a cracked account with the name you give. It goes through login and configuration like a
 * vanilla client, then stands at its spawn answering keep-alives, teleports and respawns, and can chat or run
 * commands. The server really lists it, so everyone sees it. Works on offline-mode servers (any name) and goes
 * through the active proxy like your own connection. Play packets are decoded with your connection's registries,
 * which is why the bot can only join the server you are currently on.
 */
public final class BotPlayers {
    private static final Minecraft mc = Minecraft.getInstance();

    /** Marks the bots' packet listeners so Prism's own packet hooks ignore them. */
    public interface Marker {}

    private static final Set<Connection> BOT_CONNECTIONS = ConcurrentHashMap.newKeySet();
    private final Map<String, Bot> bots = new LinkedHashMap<>();

    /** True for a connection that belongs to a bot (the packet mixins skip those). */
    public static boolean isBot(Connection c) { return BOT_CONNECTIONS.contains(c); }

    public List<String> names() { return new ArrayList<>(bots.keySet()); }

    public Bot get(String name) {
        for (var e : bots.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    /** @return an error for chat, or null when the bot started connecting. */
    public String join(String name) { return join(name, false); }

    /** @param newIp connect through Tor on a circuit of its own, so the bot has an IP different from you and the other bots */
    public String join(String name, boolean newIp) {
        if (mc.getConnection() == null || mc.isLocalServer() || mc.getCurrentServer() == null) return "Join a server first (bots connect to the server you are on)";
        if (!FakePlayers.validName(name)) return "Names are 1-16 letters, digits or _";
        if (get(name) != null) return name + " is already one of your bots";
        if (mc.getConnection().getPlayerInfo(name) != null) return name + " is already online";
        ServerData sd = mc.getCurrentServer();
        ServerAddress sa = ServerAddress.parseString(sd.ip);
        Bot bot = new Bot(name, sa.getHost(), sa.getPort(), mc.getConnection().registryAccess());
        bot.viaSource = mc.getConnection().getConnection();
        bot.ownCircuit = newIp;
        if (newIp && !Prism.proxies().tor().running()) Prism.proxies().tor().start();
        bots.put(name, bot);
        Thread t = new Thread(bot::connect, "prism-bot-" + name);
        t.setDaemon(true);
        t.start();
        return null;
    }

    public String leave(String name) {
        Bot b = get(name);
        if (b == null) return "No bot named " + name;
        b.leave("left");
        return null;
    }

    public void leaveAll() { for (Bot b : new ArrayList<>(bots.values())) b.leave("left"); }

    /** "all" or a bot name. A message starting with / is run as a command. */
    public String say(String target, String message) {
        List<Bot> targets = new ArrayList<>();
        if (target.equalsIgnoreCase("all")) targets.addAll(bots.values());
        else { Bot b = get(target); if (b == null) return "No bot named " + target; targets.add(b); }
        if (targets.isEmpty()) return "No bots online";
        int sent = 0;
        for (Bot b : targets) if (b.say(message)) sent++;
        return sent == 0 ? "Bot" + (targets.size() > 1 ? "s are" : " is") + " not in the game yet" : null;
    }

    /**
     * Make bots walk after a player. {@code target} is "all" or a bot name; {@code player} is who to follow.
     * @return an error for chat, or null.
     */
    public String follow(String target, String player) {
        List<Bot> targets = pick(target);
        if (targets == null) return "No bot named " + target;
        if (targets.isEmpty()) return "No bots online";
        int on = 0;
        for (Bot bot : targets) if (bot.inGame()) { bot.follow(player); on++; }
        return on == 0 ? "No bot is in the game yet" : null;
    }

    /** Stop bots following. */
    public String stopFollow(String target) {
        List<Bot> targets = pick(target);
        if (targets == null) return "No bot named " + target;
        for (Bot bot : targets) bot.follow(null);
        return null;
    }

    /**
     * Drop items from bots. {@code slot}: a hotbar number 1-9, or "all" (everything in the main inventory and hotbar).
     * {@code qty}: a number, or "max" for the whole stack.
     */
    public String drop(String target, String slot, String qty) {
        List<Bot> targets = pick(target);
        if (targets == null) return "No bot named " + target;
        if (targets.isEmpty()) return "No bots online";
        boolean all = slot.equalsIgnoreCase("all");
        int hotbar = -1;
        if (!all) {
            try { hotbar = Integer.parseInt(slot.trim()); } catch (NumberFormatException e) { return "Slot is 1-9 or \"all\""; }
            if (hotbar < 1 || hotbar > 9) return "Slot is 1-9 or \"all\"";
        }
        int count = qty.equalsIgnoreCase("max") ? Integer.MAX_VALUE : -1;
        if (count == -1) { try { count = Integer.parseInt(qty.trim()); } catch (NumberFormatException e) { return "Quantity is a number or \"max\""; } }
        int done = 0;
        for (Bot bot : targets) if (bot.inGame()) { if (all) bot.dropAll(); else bot.dropHotbar(hotbar - 1, count); done++; }
        return done == 0 ? "No bot is in the game yet" : null;
    }

    /** Lines of a bot's inventory for chat, or an error string wrapped in a one-element list. */
    public List<String> inventory(String botName) {
        Bot b = get(botName);
        if (b == null) return List.of("No bot named " + botName);
        if (!b.inGame()) return List.of(botName + " is not in the game yet");
        return b.inventoryLines();
    }

    private List<Bot> pick(String target) {
        if (target.equalsIgnoreCase("all")) return new ArrayList<>(bots.values());
        Bot b = get(target);
        return b == null ? null : List.of(b);
    }

    /**
     * Become one of your bots. With {@code swap} your current connection is handed to a bot first, in place: the
     * socket stays open, a bot listener takes over keep-alives, and the server never sees your old account leave.
     * Then the target bot disconnects, your session switches to its cracked account and the client reconnects as it
     * (the client needs the world state the bot never kept, so that side is a real login).
     */
    public String switchTo(String botName, boolean swap) {
        if (mc.getConnection() == null || mc.isLocalServer() || mc.getCurrentServer() == null || mc.player == null) return "Join a server first";
        Bot b = get(botName);
        if (b == null) return "No bot named " + botName;
        ServerData sd = mc.getCurrentServer();
        String oldName = mc.getUser().getName();
        String newName = b.name;
        if (swap) {
            String err = detachSelf(sd);
            if (err != null) return err;
        }
        b.leave("handed over to you");
        if (!Prism.accounts().login(newName)) return "Could not switch the session to " + newName;
        ChatUtil.info("Switching to §f" + newName + (swap ? "§7, §f" + oldName + "§7 stays online as a bot" : ""));
        mc.execute(() -> {
            if (mc.level != null) mc.level.disconnect(Component.literal("Switching to " + newName));
            mc.disconnectWithProgressScreen();
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc,
                ServerAddress.parseString(sd.ip), sd, false, null);
        });
        return null;
    }

    /**
     * Seamless switch: adopt the bot's live connection and rebuild the world from its packet log, so there is no
     * relog and the server sees no new join (no AuthMe spawn teleport / re-login). EXPERIMENTAL and unverified:
     * on any failure it logs the cause and falls back to the normal relog switch, so you are never left stuck.
     */
    public String fastSwitch(String botName, boolean swap) {
        var cpl = mc.getConnection();
        if (cpl == null || mc.player == null || mc.isLocalServer() || mc.getCurrentServer() == null) return "Join a server first";
        Bot b = get(botName);
        if (b == null) return "No bot named " + botName;
        if (!b.inGame()) return botName + " is not in the game yet";
        if (!b.worldReady()) return botName + " has not recorded a world yet (give it a moment after it joins)";
        try {
            ServerData sd = mc.getCurrentServer();
            RegistryAccess.Frozen registries = cpl.registryAccess();
            Connection botConn = b.conn;
            if (botConn == null || !botConn.isConnected()) return botName + "'s connection dropped";
            // 1) keep your old account online as a bot (swap), or let it leave.
            if (swap) { String err = detachSelf(sd); if (err != null) return err; }
            // 2) take the bot out of the bots list and stop Prism treating its connection as a bot's.
            bots.remove(b.name);
            BOT_CONNECTIONS.remove(botConn);
            // 3) tear the current world down (nulls player/level/gameMode), then build a real play listener on the
            //    bot's connection and replay the log into it on the main thread.
            mc.execute(() -> {
                try {
                    mc.clearClientLevel(new GenericMessageScreen(Component.literal("Switching...")));
                    GameProfile profile = new GameProfile(FakePlayers.offlineUuid(b.name), b.name);
                    CommonListenerCookie cookie = new CommonListenerCookie(new LevelLoadTracker(), profile,
                        mc.getTelemetryManager().createWorldSessionManager(true, null, null, UUID.randomUUID()),
                        registries, cpl.enabledFeatures(), cpl.serverBrand(), sd, null, new HashMap<>(), null,
                        new HashMap<>(), ServerLinks.EMPTY, new HashMap<>(), false);
                    ClientPacketListener listener = new ClientPacketListener(mc, botConn, cookie);
                    botConn.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)), listener);
                    botConn.packetListener = listener;
                    botConn.disconnectListener = listener;
                    for (Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> p : b.world.replayOrder()) {
                        try { p.handle(listener); } catch (Exception e) { Prism.LOG.warn("fastSwitch: replay of {} failed", p.getClass().getSimpleName(), e); }
                    }
                    ChatUtil.good("Switched to §f" + b.name + "§7 (seamless, no relog)");
                } catch (Throwable t) {
                    Prism.LOG.error("fastSwitch replay failed, falling back to relog", t);
                    ChatUtil.error("Seamless switch failed (" + t.getClass().getSimpleName() + "), relogging instead – see the log");
                    BOT_CONNECTIONS.remove(botConn);
                    if (botConn.isConnected()) botConn.disconnect(Component.literal("fastSwitch fallback"));
                    if (!Prism.accounts().login(b.name)) return;
                    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc,
                        ServerAddress.parseString(sd.ip), sd, false, null);
                }
            });
            return null;
        } catch (Throwable t) {
            Prism.LOG.error("fastSwitch setup failed, using normal switch", t);
            return switchTo(botName, swap);
        }
    }

    /**
     * Hand this client's live connection to a new bot named after the current account. The bot listener replaces
     * the vanilla one on the real connection and the vanilla listener gets a dead stand-in connection, so the
     * client's own disconnect closes nothing. Main thread.
     */
    public String detachSelf(ServerData sd) {
        var cpl = mc.getConnection();
        if (cpl == null || mc.player == null || mc.isLocalServer()) return "Join a server first";
        String name = mc.getUser().getName();
        if (get(name) != null) return name + " is already a bot";
        ServerAddress sa = ServerAddress.parseString(sd.ip);
        Connection real = cpl.getConnection();
        Bot bot = new Bot(name, sa.getHost(), sa.getPort(), cpl.registryAccess());
        bot.adopt(real, mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot());
        BOT_CONNECTIONS.add(real);
        PacketListener l = bot.listener(ClientGamePacketListener.class);
        real.packetListener = l;
        real.disconnectListener = l;
        cpl.connection = new Connection(PacketFlow.CLIENTBOUND); // what the client "disconnects" from
        bots.put(name, bot);
        return null;
    }

    /** Main thread, every client tick. */
    public void tick() {
        if (bots.isEmpty()) return;
        for (Bot b : new ArrayList<>(bots.values())) {
            b.tick();
            if (b.gone) {
                bots.remove(b.name);
                BOT_CONNECTIONS.remove(b.conn);
                ChatUtil.info("Bot §f" + b.name + "§7 " + (b.reason == null ? "disconnected" : b.reason));
            }
        }
    }

    // ---- one bot ----------------------------------------------------------------------------------------

    public final class Bot {
        public final String name;
        private final String host;
        private final int port;
        private final UUID uuid;
        private final RegistryAccess.Frozen registries;
        /** Your connection when the bot was made: ViaFabricPlus' target version is copied from it. */
        private volatile Connection viaSource;
        volatile Connection conn;
        private volatile ConnectionProtocol phase = ConnectionProtocol.LOGIN;
        private volatile boolean inPlay, gone;
        private volatile String reason;
        private volatile double x, y, z;
        private volatile float yaw, pitch;
        private volatile boolean hasPos;
        private int ticks;
        private volatile long diedAt;
        // state the bot decodes so it can be shown / followed / dropped from (everything else is still discarded)
        private final ItemStack[] inv = new ItemStack[46];        // container slots of window 0 (9-35 main, 36-44 hotbar, 45 offhand, 5-8 armor)
        private volatile int stateId, selected;
        private volatile boolean invReady;
        private final Map<Integer, double[]> entityPos = new ConcurrentHashMap<>();   // entity id -> {x,y,z}, players only
        private final Map<Integer, UUID> entityUuid = new ConcurrentHashMap<>();
        private final Map<UUID, String> tabNames = new ConcurrentHashMap<>();         // uuid -> name (from the tab list)
        private volatile String followName;
        /** Records the play stream so a fast switch can rebuild this bot's world without a relog. */
        final BotWorldLog world = new BotWorldLog();
        /** .joinplayer name true: this bot rides its own Tor circuit. */
        volatile boolean ownCircuit;
        private String circuitTag;
        private boolean ipReported;

        Bot(String name, String host, int port, RegistryAccess.Frozen registries) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.uuid = FakePlayers.offlineUuid(name);
            this.registries = registries;
        }

        public boolean inGame() { return inPlay && !gone; }
        public boolean worldReady() { return world.ready(); }
        public int logSize() { return world.size(); }
        public boolean ownCircuit() { return ownCircuit; }

        /** Take over a connection that is already in the game (the client's own, see detachSelf). */
        void adopt(Connection c, double x, double y, double z, float yaw, float pitch) {
            conn = c;
            phase = ConnectionProtocol.PLAY;
            inPlay = true;
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
            hasPos = true;
        }
        public String state() { return gone ? "gone" : inPlay ? "in game" : phase.name().toLowerCase(); }

        void follow(String player) { followName = player == null || player.isBlank() ? null : player; }
        public String following() { return followName; }

        /** Drops the whole stack (or count) from hotbar index 0-8: select it, then the vanilla drop action. */
        void dropHotbar(int index, int count) {
            Connection c = conn;
            if (c == null || index < 0 || index > 8) return;
            if (selected != index) { c.send(new ServerboundSetCarriedItemPacket(index)); selected = index; }
            if (count == Integer.MAX_VALUE) c.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, BlockPos.ZERO, Direction.DOWN));
            else for (int i = 0; i < count && i < 64; i++) c.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN));
        }

        /** Drops everything in the main inventory and hotbar: hotbar via the drop action, the rest via throw-clicks. */
        void dropAll() {
            Connection c = conn;
            if (c == null) return;
            for (int h = 0; h < 9; h++) { int cs = 36 + h; if (inv[cs] != null && !inv[cs].isEmpty()) dropHotbar(h, Integer.MAX_VALUE); }
            for (int cs = 9; cs <= 35; cs++) if (inv[cs] != null && !inv[cs].isEmpty()) throwSlot(cs);
        }

        /** A throw-click (whole stack) on a window-0 slot, for inventory slots that aren't on the hotbar. */
        private void throwSlot(int containerSlot) {
            Connection c = conn;
            if (c == null) return;
            c.send(new ServerboundContainerClickPacket(0, stateId, (short) containerSlot, (byte) 1, ContainerInput.THROW,
                Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
        }

        /** Chat lines of what the bot is holding (hotbar, armour, offhand, then the rest). */
        List<String> inventoryLines() {
            if (!invReady) return List.of(name + ": inventory not received yet");
            List<String> out = new ArrayList<>();
            StringBuilder hot = new StringBuilder("§7Hotbar: ");
            for (int h = 0; h < 9; h++) hot.append(h == selected ? "§e[" : "§f").append(itemLabel(inv[36 + h])).append(h == selected ? "§e] " : " ");
            out.add(hot.toString());
            String[] armour = {armourLabel(5, "helm"), armourLabel(6, "chest"), armourLabel(7, "legs"), armourLabel(8, "boots"), armourLabel(45, "off")};
            out.add("§7Armour/off: §f" + String.join(" ", armour));
            List<String> main = new ArrayList<>();
            for (int cs = 9; cs <= 35; cs++) if (inv[cs] != null && !inv[cs].isEmpty()) main.add(itemLabel(inv[cs]));
            out.add("§7Inventory (" + main.size() + "): §f" + (main.isEmpty() ? "empty" : String.join(", ", main)));
            return out;
        }

        private String armourLabel(int cs, String tag) { ItemStack s = inv[cs]; return tag + "=" + (s == null || s.isEmpty() ? "-" : itemLabel(s)); }

        private static String itemLabel(ItemStack s) {
            if (s == null || s.isEmpty()) return "-";
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            return path + (s.getCount() > 1 ? "x" + s.getCount() : "");
        }

        /** Worker thread: resolve, open the socket, start the handshake. */
        void connect() {
            try {
                InetSocketAddress addr = ServerNameResolver.DEFAULT.resolveAddress(new ServerAddress(host, port))
                    .map(r -> r.asInetSocketAddress()).orElseThrow(() -> new IllegalStateException("cannot resolve " + host));
                Object via = ViaBridge.targetVersion(viaSource);
                EventLoopGroupHolder holder = EventLoopGroupHolder.remote(mc.options.useNativeTransport());
                java.util.function.Supplier<Connection> open = () -> {
                    Connection made = new Connection(PacketFlow.CLIENTBOUND);
                    if (ViaBridge.setTargetVersion(made, via)) Prism.LOG.info("Bot {}: ViaFabricPlus target version {}", name, ViaBridge.describe(via));
                    Connection.connect(addr, holder, made).syncUninterruptibly();
                    return made;
                };
                Connection c;
                if (ownCircuit) {
                    TorRunner tor = Prism.proxies().tor();
                    long until = System.currentTimeMillis() + 90_000;
                    while (!tor.ready() && System.currentTimeMillis() < until) {
                        if (!tor.running() && !tor.start()) throw new IllegalStateException(tor.status());
                        Thread.sleep(250);
                    }
                    if (!tor.ready()) throw new IllegalStateException("Tor did not come up: " + tor.status());
                    circuitTag = name + "-" + Long.toHexString(System.nanoTime());
                    Connection[] made = new Connection[1];
                    String tag = circuitTag;
                    Prism.proxies().connectWith(() -> Prism.proxies().isolatedTorHandler(tag), () -> made[0] = open.get());
                    c = made[0];
                } else {
                    c = open.get();
                }
                BOT_CONNECTIONS.add(c);
                conn = c;
                c.initiateServerboundPlayConnection(host, port, listener(ClientLoginPacketListener.class));
                c.send(new ServerboundHelloPacket(name, uuid));
            } catch (Exception e) {
                reason = "could not connect: " + e.getMessage();
                gone = true;
            }
        }

        void leave(String why) {
            reason = why;
            Connection c = conn;
            if (c != null && c.isConnected()) c.disconnect(Component.literal("Prism bot " + why));
            else gone = true;
        }

        boolean say(String message) {
            Connection c = conn;
            if (!inGame() || c == null) return false;
            if (message.startsWith("/")) c.send(new ServerboundChatCommandPacket(message.substring(1)));
            else c.send(new ServerboundChatPacket(message, Instant.now(), 0L, null, new LastSeenMessages.Update(0, new BitSet(), LastSeenMessages.Update.IGNORE_CHECKSUM)));
            return true;
        }

        /** Walks one tick toward the followed player if the bot can see them. Returns true if it moved. */
        private boolean stepTowardFollow(String player) {
            double[] tp = targetPos(player);
            if (tp == null) return false;
            double dx = tp[0] - x, dz = tp[2] - z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= 2.0) return false; // close enough
            double step = Math.min(0.215, d - 2.0); // ~4.3 blocks/s, vanilla walk
            x += dx / d * step;
            z += dz / d * step;
            y = tp[1]; // no world knowledge: ride the target's height, the server corrects us
            float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
            this.yaw = yaw;
            return true;
        }

        /** The followed player's position, by exact name then by closest loaded player, or null if not seen. */
        private double[] targetPos(String player) {
            for (var e : tabNames.entrySet())
                if (e.getValue().equalsIgnoreCase(player)) {
                    for (var pe : entityUuid.entrySet()) if (pe.getValue().equals(e.getKey())) return entityPos.get(pe.getKey());
                }
            return null;
        }

        void tick() {
            Connection c = conn;
            if (c == null) return;
            c.tick();
            if (!c.isConnected() && !c.isConnecting()) {
                c.handleDisconnection();
                gone = true;
                return;
            }
            if (!inPlay || !hasPos) return;
            if (ownCircuit && !ipReported) {
                ipReported = true;
                String tag = circuitTag;
                Thread t = new Thread(() -> {
                    try {
                        String ip = ProxyManager.publicIp(new ProxyManager.Proxy("tor-" + name, ProxyManager.Type.SOCKS5, TorRunner.HOST, TorRunner.SOCKS_PORT, "prism-" + tag, "x"));
                        mc.execute(() -> ChatUtil.info("Bot \u00a7f" + name + "\u00a77 is in from \u00a7f" + ip + "\u00a77 (own Tor circuit)"));
                    } catch (Exception ignored) {}
                }, "prism-bot-ip");
                t.setDaemon(true);
                t.start();
            }
            ticks++;
            boolean moved = false;
            String fn = followName;
            if (fn != null) moved = stepTowardFollow(fn);
            if (moved || ticks % 20 == 0) c.send(new ServerboundMovePlayerPacket.Pos(x, y, z, true, false));
            c.send(ServerboundClientTickEndPacket.INSTANCE);
            if (diedAt != 0 && System.currentTimeMillis() - diedAt > 1500) {
                diedAt = 0;
                c.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
        }

        // ---- protocol -------------------------------------------------------------------------------------

        @SuppressWarnings("unchecked")
        <T extends PacketListener> T listener(Class<T> iface) {
            InvocationHandler h = this::invoke;
            return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface, Marker.class}, h);
        }

        private Object invoke(Object proxy, Method m, Object[] args) {
            switch (m.getName()) {
                case "flow": return PacketFlow.CLIENTBOUND;
                case "protocol": return phase;
                case "isAcceptingMessages": return conn != null && conn.isConnected();
                case "shouldHandleMessage": return true;
                case "shouldPropagateHandlingExceptions": return false;
                case "onDisconnect": {
                    DisconnectionDetails d = (DisconnectionDetails) args[0];
                    if (reason == null) {
                        String why = d.reason().getString();
                        if (!inPlay && ownCircuit && why.toLowerCase(Locale.ROOT).contains("time")) why += " - Tor could not reach " + host + ":" + port + " in 60 s (no exit allowed the port, or the server blocks Tor). Try again or .proxy tor new";
                        reason = "disconnected: " + why;
                    }
                    gone = true;
                    return null;
                }
                case "createDisconnectionInfo": return new DisconnectionDetails((Component) args[0]);
                case "onPacketError": {
                    Prism.LOG.warn("Bot {}: error handling {}", name, args[0].getClass().getSimpleName(), (Throwable) args[1]);
                    return null;
                }
                case "toString": return "PrismBot(" + name + ")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: break;
            }
            if (args != null && args.length == 1 && args[0] instanceof Packet<?> p) {
                if (phase == ConnectionProtocol.PLAY) try { world.record(p); } catch (Exception ignored) {}
                try { handle(p); } catch (Exception e) { Prism.LOG.warn("Bot {}: {} failed", name, p.getClass().getSimpleName(), e); }
                return null;
            }
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == float.class) return 0f;
            if (r == double.class) return 0d;
            return null;
        }

        private void handle(Packet<?> p) {
            Connection c = conn;
            if (p instanceof ClientboundHelloPacket) {
                reason = "kicked: the server is in online mode, cracked bots can't log in there";
                c.disconnect(Component.literal("online mode"));
            } else if (p instanceof ClientboundLoginCompressionPacket lc) {
                c.setupCompression(lc.getCompressionThreshold(), false);
            } else if (p instanceof ClientboundLoginFinishedPacket) {
                enterConfiguration(ServerboundLoginAcknowledgedPacket.INSTANCE);
            } else if (p instanceof ClientboundLoginDisconnectPacket ld) {
                reason = "refused: " + ld.reason().getString();
            } else if (p instanceof ClientboundCustomQueryPacket q) {
                c.send(new ServerboundCustomQueryAnswerPacket(q.transactionId(), null));
            } else if (p instanceof ClientboundCookieRequestPacket cr) {
                c.send(new ServerboundCookieResponsePacket(cr.key(), null));
            } else if (p instanceof ClientboundKeepAlivePacket ka) {
                c.send(new ServerboundKeepAlivePacket(ka.getId()));
            } else if (p instanceof ClientboundPingPacket ping) {
                c.send(new ServerboundPongPacket(ping.getId()));
            } else if (p instanceof ClientboundSelectKnownPacks) {
                c.send(new ServerboundSelectKnownPacks(List.of()));
            } else if (p instanceof ClientboundFinishConfigurationPacket) {
                enterPlay();
            } else if (p instanceof ClientboundResourcePackPushPacket rp) {
                // pretend to have it: required packs would kick a decline
                c.send(new ServerboundResourcePackPacket(rp.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
                c.send(new ServerboundResourcePackPacket(rp.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
                c.send(new ServerboundResourcePackPacket(rp.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
            } else if (p instanceof ClientboundDisconnectPacket d) {
                reason = "kicked: " + d.reason().getString();
            } else if (p instanceof ClientboundStartConfigurationPacket) {
                inPlay = false;
                hasPos = false;
                enterConfiguration(ServerboundConfigurationAcknowledgedPacket.INSTANCE);
            } else if (p instanceof ClientboundLoginPacket) {
                inPlay = true;
            } else if (p instanceof ClientboundPlayerPositionPacket pos) {
                PositionMoveRotation cur = new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch);
                PositionMoveRotation abs = PositionMoveRotation.calculateAbsolute(cur, pos.change(), pos.relatives());
                x = abs.position().x; y = abs.position().y; z = abs.position().z;
                yaw = abs.yRot(); pitch = abs.xRot();
                hasPos = true;
                // 26.3: the accept carries the resulting position (vanilla sends no extra PosRot any more)
                c.send(new ServerboundAcceptTeleportationPacket(pos.id(), x, y, z, yaw, pitch));
            } else if (p instanceof ClientboundSetHealthPacket h) {
                if (h.getHealth() <= 0 && diedAt == 0) diedAt = System.currentTimeMillis();
            } else if (p instanceof ClientboundContainerSetContentPacket sc && sc.containerId() == 0) {
                List<ItemStack> items = sc.items();
                for (int i = 0; i < inv.length && i < items.size(); i++) inv[i] = items.get(i);
                stateId = sc.stateId();
                invReady = true;
            } else if (p instanceof ClientboundContainerSetSlotPacket ss && ss.getContainerId() == 0) {
                if (ss.getSlot() >= 0 && ss.getSlot() < inv.length) inv[ss.getSlot()] = ss.getItem();
                stateId = ss.getStateId();
                invReady = true;
            } else if (p instanceof ClientboundSetPlayerInventoryPacket pi) {
                int cs = invIndexToContainer(pi.slot());
                if (cs >= 0) { inv[cs] = pi.contents(); invReady = true; }
            } else if (p instanceof ClientboundSetHeldSlotPacket hs) {
                if (hs.slot() >= 0 && hs.slot() < 9) selected = hs.slot();
            } else if (p instanceof ClientboundPlayerInfoUpdatePacket pit) {
                for (var entry : pit.entries()) if (entry.profile() != null) tabNames.put(entry.profileId(), entry.profile().name());
            } else if (p instanceof ClientboundAddEntityPacket ae) {
                entityUuid.put(ae.getId(), ae.getUUID());
                entityPos.put(ae.getId(), new double[]{ae.getX(), ae.getY(), ae.getZ()});
            } else if (p instanceof ClientboundTeleportEntityPacket te) {
                double[] cur = entityPos.get(te.id());
                if (cur != null) { Vec3 v = te.change().position(); cur[0] = v.x; cur[1] = v.y; cur[2] = v.z; }
            } else if (p instanceof ClientboundEntityPositionSyncPacket ps) {
                double[] cur = entityPos.get(ps.id());
                if (cur != null) { Vec3 v = ps.position().endPosition(); cur[0] = v.x; cur[1] = v.y; cur[2] = v.z; }
            } else if (p instanceof ClientboundRemoveEntitiesPacket re) {
                re.entityIds().forEach((int id) -> { entityPos.remove(id); entityUuid.remove(id); });
            }
        }

        /** Inventory index (0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand) to a window-0 container slot, or -1. */
        private static int invIndexToContainer(int i) {
            if (i >= 0 && i <= 8) return 36 + i;      // hotbar
            if (i >= 9 && i <= 35) return i;          // main
            if (i >= 36 && i <= 39) return 8 - (i - 36); // armour: boots..helmet -> 8..5
            if (i == 40) return 45;                   // offhand
            return -1;
        }

        /** Login finished, or the server sent us back to configuration mid-game. */
        private void enterConfiguration(Packet<?> ack) {
            Connection c = conn;
            phase = ConnectionProtocol.CONFIGURATION;
            c.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, listener(ClientConfigurationPacketListener.class));
            c.send(ack);
            c.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            c.send(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")));
            c.send(new ServerboundClientInformationPacket(new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0,
                HumanoidArm.RIGHT, false, true, ParticleStatus.MINIMAL)));
        }

        private void enterPlay() {
            Connection c = conn;
            phase = ConnectionProtocol.PLAY;
            var decorator = RegistryFriendlyByteBuf.decorator(registries);
            c.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(decorator), listener(ClientGamePacketListener.class));
            c.send(ServerboundFinishConfigurationPacket.INSTANCE);
            c.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(decorator, new GameProtocols.Context() {
                @Override public boolean hasInfiniteMaterials() { return false; }
                @Override public boolean canUseCommandBlocks() { return false; }
            }));
        }
    }

    public static List<String> empty() { return Collections.emptyList(); }
}
