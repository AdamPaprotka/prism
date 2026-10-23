package dev.prismglass.scan;

import java.net.URI;
import java.util.*;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;

/**
 * Always-on, passive observer of what the server pushes (configuration AND play phase, so it also sees what arrived
 * before PluginScanner was switched on). It only counts and remembers the shape of things: channel ids, ping ids and
 * timing, resource pack hosts, how often the command tree was sent. Never message text, never payload
 * bytes (26.1 keeps only the channel id of an unknown custom payload anyway).
 *
 * <p>Called from the netty thread for every packet, so it stays cheap. A new session starts when the connection
 * ends, or when a configuration packet follows play packets (the server re-configured us, e.g. a proxy switching
 * backends).
 */
public final class ServerIntel {
    private static final Object LOCK = new Object();
    private static final int MAX_PINGS = 256, MAX_PACKS = 8, MAX_CHANNELS = 128;

    public record Channel(String id, String phase, int count, long firstMs) {}
    public record Ping(int id, long ms) {}
    public record Pack(String host, boolean required) {}

    /** Immutable copy of the observed state. */
    public record Snapshot(long startedMs, List<Channel> channels, List<Ping> pings, int pingTotal, List<Pack> packs,
                           int treeCount, long firstTreeMs, long lastTreeMs, String brand, boolean registerSeen) {
        public static Snapshot empty() {
            return new Snapshot(0, List.of(), List.of(), 0, List.of(), 0, 0, 0, null, false);
        }
    }

    // state, guarded by LOCK
    private static long startedMs;
    private static volatile boolean playSeen;
    private static boolean registerSeen;
    private static final Map<String, long[]> channelStats = new LinkedHashMap<>(); // id -> {count, firstMs, config?1:0}
    private static final ArrayDeque<Ping> pings = new ArrayDeque<>();
    private static int pingTotal;
    private static final List<Pack> packs = new ArrayList<>();
    private static int treeCount;
    private static long firstTreeMs, lastTreeMs;
    private static String brand;

    private ServerIntel() {}

    /** Netty thread. {@code listener} tells the phase. */
    public static void onReceive(PacketListener listener, Packet<?> packet) {
        boolean config = listener instanceof ClientConfigurationPacketListener;
        // chunk data and entity updates make up nearly all play traffic: skip them without taking the lock
        if (!config && playSeen && !(packet instanceof ClientboundCustomPayloadPacket || packet instanceof ClientboundPingPacket
            || packet instanceof ClientboundResourcePackPushPacket || packet instanceof ClientboundCommandsPacket)) return;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (config && playSeen) clear();
            if (!config) playSeen = true;
            if (startedMs == 0) startedMs = now;
            if (packet instanceof ClientboundCustomPayloadPacket p) {
                String id = p.payload().type().id().toString();
                if (p.payload() instanceof BrandPayload b) brand = b.brand();
                if (id.equals("minecraft:register") || id.equals("minecraft:unregister")) registerSeen = true;
                long[] st = channelStats.get(id);
                if (st != null) st[0]++;
                else if (channelStats.size() < MAX_CHANNELS) channelStats.put(id, new long[]{1, now, config ? 1 : 0});
            } else if (packet instanceof ClientboundPingPacket p) {
                pingTotal++;
                pings.addLast(new Ping(p.getId(), now));
                if (pings.size() > MAX_PINGS) pings.removeFirst();
            } else if (packet instanceof ClientboundResourcePackPushPacket p) {
                if (packs.size() < MAX_PACKS) packs.add(new Pack(hostOf(p.url()), p.required()));
            } else if (packet instanceof ClientboundCommandsPacket) {
                treeCount++;
                if (firstTreeMs == 0) firstTreeMs = now;
                lastTreeMs = now;
            }
        }
    }

    public static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "?" : h.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Forget everything (connection ended). */
    public static void reset() {
        synchronized (LOCK) { clear(); }
    }

    private static void clear() {
        startedMs = 0;
        playSeen = false;
        registerSeen = false;
        channelStats.clear();
        pings.clear();
        pingTotal = 0;
        packs.clear();
        treeCount = 0;
        firstTreeMs = lastTreeMs = 0;
        brand = null;
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            List<Channel> ch = new ArrayList<>();
            for (Map.Entry<String, long[]> e : channelStats.entrySet())
                ch.add(new Channel(e.getKey(), e.getValue()[2] == 1 ? "config" : "play", (int) e.getValue()[0], e.getValue()[1]));
            return new Snapshot(startedMs, List.copyOf(ch), List.copyOf(pings), pingTotal, List.copyOf(packs),
                treeCount, firstTreeMs, lastTreeMs, brand, registerSeen);
        }
    }
}
