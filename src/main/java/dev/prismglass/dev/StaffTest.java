package dev.prismglass.dev;

import com.mojang.authlib.GameProfile;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.client.StaffWatcher;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Dev only (-Pstafftest): StaffWatcher against fake players injected into the client (tab-list packets handled by the
 * real ClientPacketListener + RemotePlayer entities): rank detection, look detection, cooldown, spectator switch next
 * to you, leave/join vanish checks, probe answers (and one real probe round trip to the integrated server).
 * Logs "[stafftest]" lines, takes prism-staffpop.png mid head-pop, exits.
 */
public final class StaffTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t, passed, failed;
    private static boolean requested;
    private static final Map<String, RemotePlayer> bots = new HashMap<>();
    private static final Map<String, GameProfile> profiles = new HashMap<>();
    private static final Map<String, Boolean> facing = new HashMap<>();
    private static int nextId = 900_000;

    private StaffTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[stafftest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static UUID id(String name) { return UUID.nameUUIDFromBytes(("stafftest:" + name).getBytes()); }

    /** Tab-list update through the client's real packet handler. */
    private static void tabUpdate(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions, String name, GameType mode, Component display) {
        try {
            GameProfile profile = profiles.computeIfAbsent(name, n -> new GameProfile(id(n), n));
            var entry = new ClientboundPlayerInfoUpdatePacket.Entry(profile.id(), profile, true, 20, mode, display, true, 0, null);
            var packet = new ClientboundPlayerInfoUpdatePacket(actions, List.of());
            var f = ClientboundPlayerInfoUpdatePacket.class.getDeclaredField("entries");
            f.setAccessible(true);
            f.set(packet, List.of(entry));
            mc.getConnection().handlePlayerInfoUpdate(packet);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void addTab(String name, Component display) {
        tabUpdate(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), name, GameType.SURVIVAL, display);
    }

    private static void removeTab(String name) {
        mc.getConnection().handlePlayerInfoRemove(new ClientboundPlayerInfoRemovePacket(List.of(id(name))));
    }

    /** A system chat line, through both the real handler (shows in chat) and Prism's packet event. */
    private static void chat(String text) {
        var p = new ClientboundSystemChatPacket(Component.literal(text), false);
        Prism.modules().get(StaffWatcher.class).onPacketReceive(new PacketEvent(p));
        mc.getConnection().handleSystemChat(p);
    }

    private static void spawn(String name, double x, double z, boolean lookAtMe) {
        RemotePlayer rp = new RemotePlayer(mc.level, profiles.computeIfAbsent(name, n -> new GameProfile(id(n), n)));
        rp.setId(nextId++);
        rp.snapTo(x, mc.player.getY(), z, 0, 0);
        mc.level.addEntity(rp);
        bots.put(name, rp);
        facing.put(name, lookAtMe);
    }

    private static void despawn(String name) {
        RemotePlayer rp = bots.remove(name);
        if (rp != null) mc.level.removeEntity(rp.getId(), Entity.RemovalReason.DISCARDED);
    }

    /** Keep every bot still, either looking straight at our eyes or straight away from us. */
    private static void aimBots() {
        Vec3 me = mc.player.getEyePosition();
        for (var e : bots.entrySet()) {
            RemotePlayer rp = e.getValue();
            Vec3 d = me.subtract(rp.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            if (!facing.get(e.getKey())) { yaw += 180f; pitch = 0; }
            rp.setYRot(yaw);
            rp.setXRot(pitch);
            rp.setYHeadRot(yaw);
            rp.yHeadRotO = yaw;
        }
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismstaff-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        StaffWatcher sw = Prism.modules().get(StaffWatcher.class);
        if (!bots.isEmpty()) aimBots();

        if (t == 20) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            run(s, "gamerule advance_time false");
            run(s, "time set day");
            run(s, "tp @p 0.5 -60 0.5 0 0");
            sw.staff.parse("");
            sw.lookTime.parse("0.5");
            sw.cooldown.parse("20");
            sw.probeInterval.parse("2");
            sw.setEnabled(true);
        }
        if (t == 30) {
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            Prism.commands().handle(".staff add Watcher");
            addTab("Steve2", Component.literal("§c[Admin] §fSteve2"));
            addTab("Bob", Component.literal("§7[Member] Bob"));
            addTab("Modeler", Component.literal("§e[VIP] Modeler"));
            addTab("Watcher", null);
            addTab("Ghost", null);
            addTab("Sneaky", null);
            spawn("Steve2", 0.5, 10.5, true);
            spawn("Bob", 3.5, 10.5, true);
            spawn("Modeler", -2.5, 10.5, true);
            spawn("Watcher", 6.5, 12.5, false);
            spawn("Ghost", -5.5, 6.5, false);
            spawn("Sneaky", 5.5, 4.5, false);
        }
        if (t == 48) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "prism-staffpop.png", mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        if (t == 55) {
            check("rank [Admin] looking at us pops once (pops=" + sw.pops + ", last=" + sw.lastPopName + ")", sw.pops == 1 && sw.lastPopName.equals("Steve2"));
            String online = String.join(", ", sw.onlineStaff()).replaceAll("§.", "");
            log("online staff: " + online);
            check("staff = Steve2 (admin) + Watcher (staff list); Bob/Modeler/Ghost not staff",
                online.contains("Steve2 (admin)") && online.contains("Watcher (staff list)") && !online.contains("Bob") && !online.contains("Modeler") && !online.contains("Ghost"));
        }
        if (t == 80) facing.put("Watcher", true);
        if (t == 100) check("staff-list player turning to look pops (pops=" + sw.pops + ", last=" + sw.lastPopName + ")", sw.pops == 2 && sw.lastPopName.equals("Watcher"));
        if (t == 110) facing.put("Steve2", false);
        if (t == 115) facing.put("Steve2", true);
        if (t == 140) check("cooldown: Steve2 looking again within 20 s does not pop (pops=" + sw.pops + ")", sw.pops == 2);

        if (t == 150) { // Ghost goes into spectator right next to us: entity removed + game mode update
            despawn("Ghost");
            tabUpdate(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), "Ghost", GameType.SPECTATOR, null);
        }
        if (t == 160) check("spectator switch next to us pops (pops=" + sw.pops + ", last=" + sw.lastPopName + " " + sw.lastPopWhat + ")",
            sw.pops == 3 && sw.lastPopName.equals("Ghost") && sw.lastPopWhat.contains("spectator"));

        if (t == 170) { // a real leave, with its message: teaches that this server shows leave messages
            chat("Bob left the game");
            despawn("Bob");
            removeTab("Bob");
        }
        if (t == 230) check("real leave (with message) does not pop (pops=" + sw.pops + ")", sw.pops == 3);
        if (t == 240) { // Sneaky (5 blocks away) drops off the tab list with no message
            despawn("Sneaky");
            removeTab("Sneaky");
        }
        if (t == 300) check("leave without a message next to us = vanished (pops=" + sw.pops + ", last=" + sw.lastPopName + " " + sw.lastPopWhat + ")",
            sw.pops == 4 && sw.lastPopName.equals("Sneaky") && sw.lastPopWhat.contains("vanished"));
        if (t == 310) { chat("Carl joined the game"); addTab("Carl", null); }
        if (t == 320) addTab("Sneaky", null);
        if (t == 380) {
            String online = String.join(", ", sw.onlineStaff()).replaceAll("§.", "");
            log("online staff: " + online);
            check("unvanished Sneaky now counts as staff (vanish), Carl doesn't", online.contains("Sneaky (vanish)") && !online.contains("Carl"));
            check("real probe round trip to the server answered (answers=" + sw.probeReplies + ")", sw.probeReplies > 0);
        }
        if (t == 381) sw.probeInterval.parse("120"); // no more real probes: the injected answer below decides
        if (t == 385) {
            List<ClientboundCommandSuggestionsPacket.Entry> names = new ArrayList<>();
            for (String n : List.of("@a", "@p", mc.player.getGameProfile().name(), "Steve2", "HiddenMod"))
                names.add(new ClientboundCommandSuggestionsPacket.Entry(n, Optional.empty()));
            PacketEvent ev = new PacketEvent(new ClientboundCommandSuggestionsPacket(StaffWatcher.PROBE_ID, 0, 0, names));
            sw.onPacketReceive(ev);
            check("probe answer is swallowed (not shown to the chat box)", ev.isCancelled());
        }
        if (t == 390) {
            check("probe name missing from the tab list = vanished (pops=" + sw.pops + ", last=" + sw.lastPopName + " " + sw.lastPopWhat + ")",
                sw.pops == 5 && sw.lastPopName.equals("HiddenMod"));
            String online = String.join(", ", sw.onlineStaff()).replaceAll("§.", "");
            check("HiddenMod listed as vanished: " + online, online.contains("HiddenMod (vanished)"));
        }
        if (t == 400) {
            log("RESULT passed=" + passed + " failed=" + failed);
            mc.stop();
        }
    }
}
