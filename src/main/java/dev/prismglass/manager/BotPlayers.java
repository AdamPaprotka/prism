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
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
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
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
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
import net.minecraft.world.phys.Vec3;

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
            if (ticks % 20 == 0) c.send(new ServerboundMovePlayerPacket.Pos(x, y, z, true, false));
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
            }
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
