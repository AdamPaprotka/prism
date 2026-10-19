package dev.prismglass.manager;

import com.mojang.authlib.GameProfile;
import dev.prismglass.util.ChatUtil;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * Client-side fake players (.joinplayer / .leaveplayer): a tab-list entry plus a player entity standing where you
 * are, wearing your skin and gear, with the usual "joined the game" line. Nothing is sent to the server; they are
 * targets for testing combat modules and nothing else can see them.
 */
public final class FakePlayers {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final String[] NAMES = {"Steve", "Alex", "Notch", "Herobrine", "xXslayerXx", "Dream", "Fit", "Popbob",
        "Hausemaster", "Jared", "Kinorana", "Sato86", "Offtopia", "Rusher", "Armorsmith", "Etika"};

    private final Map<String, RemotePlayer> bots = new LinkedHashMap<>();
    private int nextId = 950_000;

    /** The uuid an offline-mode server gives this name. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean validName(String name) { return name.matches("[A-Za-z0-9_]{1,16}"); }

    public String randomName() {
        Random r = new Random();
        for (int i = 0; i < 20; i++) {
            String n = NAMES[r.nextInt(NAMES.length)] + (r.nextBoolean() ? "" : String.valueOf(r.nextInt(10, 9999)));
            if (!bots.containsKey(n) && n.length() <= 16) return n;
        }
        return "Player" + r.nextInt(100, 999);
    }

    /** @return an error for chat, or null when the player joined. */
    public String join(String name) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return "Join a world first";
        if (!validName(name)) return "Names are 1-16 letters, digits or _";
        if (bots.containsKey(name) || mc.getConnection().getPlayerInfo(name) != null) return name + " is already online";
        ClientPacketListener net = mc.getConnection();
        GameProfile profile = new GameProfile(offlineUuid(name), name, mc.player.getGameProfile().properties()); // your skin
        PlayerInfo entry = new PlayerInfo(profile, false);
        net.playerInfoMap.put(profile.id(), entry);
        net.listedPlayers.add(entry);

        RemotePlayer e = new RemotePlayer(mc.level, profile);
        e.setId(nextId++);
        e.snapTo(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot());
        e.setYHeadRot(mc.player.getYHeadRot());
        e.yHeadRotO = e.yHeadRot;
        e.yBodyRotO = e.yBodyRot = mc.player.yBodyRot;
        e.setHealth(mc.player.getHealth());
        e.getInventory().replaceWith(mc.player.getInventory());
        mc.level.addEntity(e);
        bots.put(name, e);
        ChatUtil.send(Component.translatable("multiplayer.player.joined", Component.literal(name)).withStyle(ChatFormatting.YELLOW));
        return null;
    }

    /** @return an error for chat, or null when the player left. */
    public String leave(String name) {
        String key = null;
        for (String n : bots.keySet()) if (n.equalsIgnoreCase(name)) key = n;
        if (key == null) return "No fake player named " + name;
        remove(key);
        return null;
    }

    /** Removes the most recently joined fake player; null when there is none. */
    public String leaveLast() {
        if (bots.isEmpty()) return null;
        String last = null;
        for (String n : bots.keySet()) last = n;
        remove(last);
        return last;
    }

    private void remove(String name) {
        RemotePlayer e = bots.remove(name);
        if (mc.level != null && e != null) mc.level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
        ClientPacketListener net = mc.getConnection();
        if (net != null) {
            PlayerInfo entry = net.playerInfoMap.remove(offlineUuid(name));
            if (entry != null) net.listedPlayers.remove(entry);
        }
        ChatUtil.send(Component.translatable("multiplayer.player.left", Component.literal(name)).withStyle(ChatFormatting.YELLOW));
    }

    public List<String> names() { return new ArrayList<>(bots.keySet()); }

    /** World gone: the entities and tab entries went with it. */
    public void clear() { bots.clear(); }
}
