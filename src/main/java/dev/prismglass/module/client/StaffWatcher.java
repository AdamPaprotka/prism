package dev.prismglass.module.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.util.Mth;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Tells you when staff are watching you, with the totem pop animation showing their head instead of a totem.
 *
 * <p>Staff = names on the Staff list ({@code .staff add}), a staff word in their tab-list rank or team prefix
 * ([Admin], [Sr.Mod]...), anyone seen in spectator mode, and anyone caught vanishing. "Watching" is what the client
 * can actually see:
 * <ul>
 *   <li>a loaded staff player keeps their look ray on your hitbox (LookAngle, LookTime)</li>
 *   <li>a player next to you switches to spectator - servers stop sending spectators to non-spectators, so that's
 *       the last you see of them, and it's how people start spectating someone</li>
 *   <li>AntiVanish: a player next to you leaves the tab list without a leave message</li>
 * </ul>
 *
 * <p>AntiVanish (vanish plugins hide the player, so it has to be inferred):
 * <ul>
 *   <li>Leave check on the LISTED tab: a staff member hidden from TAB while still connected, or who left TAB while
 *       their body is still in the world, has vanished. Leaving TAB with no leave message too, once this server has
 *       shown a real leave message (servers with leave messages off would flag everyone), or right next to you.
 *       Entries listed under 1.5 s and bursts of 4+ changes (shard/server switch) are ignored.</li>
 *   <li>Join check, the same in reverse: back in TAB = visible again; appearing with no join message = unvanished.</li>
 *   <li>Probe: asks the server to tab-complete ProbeCommand ("minecraft:msg ", the vanilla command, so plugin
 *       /msg commands that hide vanished players are skipped) every ProbeInterval seconds. Names it suggests that
 *       aren't in the tab list are online but hidden. 4+ such names = it completes players of the whole network,
 *       so then only known staff names count.</li>
 *   <li>Invisible staff bodies near you.</li>
 *   <li>ChatCheck: a player chat message (it carries the sender's UUID) from someone connected but not listed.</li>
 *   <li>DespawnCheck: a staff member's body disappears well inside entity tracking range while they stay connected.</li>
 *   <li>VanishTags: "[V]" / "vanished" in a tab name, team prefix or team name.</li>
 * </ul>
 * The probe rotates through every ProbeCommand and skips ones that keep answering with no names.
 *
 * <p>AutoLog: chosen alert kinds (LogOn) disconnect you, switch every module off (Panic), or both.
 * Visuals: a glass HUD panel of staff online / hidden / watching, a red screen-edge pulse and banner while you're
 * watched, staff ESP with their look line, and fading beacons where someone vanished or a ghost was heard.
 * DonutStaff adds DonutSMP's staff accounts to the staff list; RankMarkers catches star-marked ranks.
 */
public class StaffWatcher extends Module {
    public final TextSetting staff = text("Staff", "", "Staff names, comma separated (or .staff add <name>).");
    public final BoolSetting ranks = bool("DetectRanks", true, "Count players with a staff word in their tab-list rank or team prefix.");
    public final TextSetting rankWords = text("RankWords",
        "owner,coowner,admin,administrator,headadmin,mod,moderator,jrmod,srmod,trialmod,helper,jrhelper,srhelper,staff,manager,dev,developer,support,sysadmin",
        "Staff words, matched as whole words in prefixes ([Sr.Mod] = sr + mod).").visibleWhen(ranks::get);
    public final TextSetting rankMarkers = text("RankMarkers", "★☆✦✧✪✯✰⭐",
        "Symbols that mark a staff rank in prefixes (DonutSMP uses stars).").visibleWhen(ranks::get);
    public final BoolSetting donutStaff = bool("DonutStaff", true, "Built-in DonutSMP staff names count as your staff list.");
    public final BoolSetting spectators = bool("Spectators", true, "Count anyone seen in spectator mode as staff.");
    public final NumberSetting range = num("Range", 64, 8, 256, 1, "How far away someone can watch you from.");
    public final NumberSetting lookAngle = num("LookAngle", 8, 1, 45, 1, "How closely their crosshair has to be on you, in degrees.");
    public final NumberSetting lookTime = num("LookTime", 0.5, 0, 5, 0.05, "Seconds they have to keep looking before it counts.");
    public final BoolSetting throughWalls = bool("ThroughWalls", true, "Count looks through blocks too.");
    public final BoolSetting headPop = bool("HeadPop", true, "Totem pop animation with the staff member's head instead of the totem.");
    public final BoolSetting particles = bool("Particles", true, "Totem particles around you with the pop.").visibleWhen(headPop::get);
    public final ModeSetting sound = mode("Sound", "Ping", "Sound when someone is watching you.", "Ping", "Totem", "None");
    public final NumberSetting cooldown = num("Cooldown", 20, 0, 300, 1, "Seconds before the same person can trigger again.");
    public final BoolSetting onlineAlert = bool("OnlineAlert", true, "Chat message when staff join, switch to spectator, or are online when you join.");
    public final BoolSetting nearAlert = bool("NearAlert", true, "Chat message when a staff member comes within NearRange.");
    public final NumberSetting nearRange = num("NearRange", 24, 4, 128, 1, "Distance for NearAlert.").visibleWhen(nearAlert::get);
    public final BoolSetting antiVanish = bool("AntiVanish", true, "Catch players going into / out of vanish.");
    public final BoolSetting leaveCheck = bool("LeaveCheck", true, "Tab-list leave/join without a chat message = vanish/unvanish.").visibleWhen(antiVanish::get);
    public final BoolSetting probe = bool("Probe", true, "Ask the server to tab-complete ProbeCommand and look for names missing from the tab list.").visibleWhen(antiVanish::get);
    public final TextSetting probeCommand = text("ProbeCommand", "minecraft:msg,minecraft:tell,minecraft:w", "Commands whose completion lists players (no slash, comma separated, asked in turn). The minecraft: prefix skips plugin /msg commands that hide vanished players.").visibleWhen(() -> antiVanish.get() && probe.get());
    public final BoolSetting ghostSense = bool("GhostSense", true, "Sounds/block breaks from a spot nobody visible could have caused (chests, doors, blocks): 2 close together = someone hidden is there.").visibleWhen(antiVanish::get);
    public final NumberSetting ghostRange = num("GhostRange", 32, 8, 64, 1, "How far away GhostSense listens.").visibleWhen(() -> antiVanish.get() && ghostSense.get());
    public final NumberSetting probeInterval = num("ProbeInterval", 10, 2, 120, 1, "Seconds between probes.").visibleWhen(() -> antiVanish.get() && probe.get());
    public final BoolSetting chatCheck = bool("ChatCheck", true, "Someone chatting who isn't in the tab list is online but hidden.").visibleWhen(antiVanish::get);
    public final BoolSetting despawnCheck = bool("DespawnCheck", true, "A staff member's body disappearing close to you while they stay connected = vanished (or teleported).").visibleWhen(antiVanish::get);
    public final NumberSetting despawnRange = num("DespawnRange", 32, 8, 64, 1, "Bodies disappearing closer than this count (players are tracked from 48+ blocks, further is just out of range).").visibleWhen(() -> antiVanish.get() && despawnCheck.get());
    public final BoolSetting vanishTags = bool("VanishTags", true, "A vanish marker in a tab name, team prefix or team name ([V], vanished) counts as vanished staff.").visibleWhen(antiVanish::get);
    public final BoolSetting ignoreNpcs = bool("IgnoreNPCs", true, "Ignore fake players (ZNPCs, Citizens, mannequins): a body never backed by a real connection, Citizens' version-2 UUIDs, and NPCNames.");
    public final TextSetting npcNames = text("NPCNames", "", "Extra NPC names to always ignore, comma separated (a leading/trailing * is a wildcard).").visibleWhen(ignoreNpcs::get);

    public final ModeSetting autoLog = mode("AutoLog", "Off", "When a LogOn alert fires: leave the server, switch every module off, or both.", "Off", "Disconnect", "Panic", "Both");
    public final ListSetting logOn = choices("LogOn", "Watching,Spectator,Vanish", "Alerts that trigger AutoLog.", false, 0,
        "Watching", "Spectator", "Vanish", "Ghost", "Invisible", "Near", "Joined").visibleWhen(() -> !autoLog.is("Off"));
    public final BoolSetting logListedOnly = bool("LogListedOnly", false, "Only names on your Staff list (or DonutStaff) trigger AutoLog.").visibleWhen(() -> !autoLog.is("Off"));
    public final BoolSetting logOnce = bool("LogOnce", true, "Set AutoLog back to Off after it fired, so rejoining doesn't log you straight out again.").visibleWhen(() -> !autoLog.is("Off"));

    public final BoolSetting hud = bool("Hud", true, "Glass panel: staff online, hidden ones, who is watching (drag it in the HUD editor).");
    public final BoolSetting watchedOverlay = bool("WatchedOverlay", true, "Red pulse round the screen edges and a banner while staff look at you.");
    public final BoolSetting staffEsp = bool("StaffESP", true, "Box and look line on loaded staff (red while they look at you).");
    public final BoolSetting markers = bool("Markers", true, "Fading beacons where someone vanished, went spectator or a ghost was heard.");
    public final NumberSetting markerTime = num("MarkerTime", 90, 10, 600, 5, "Seconds a marker stays.").visibleWhen(markers::get);
    public final ColorSetting staffColor = color("StaffColor", 0xFFFFB020, "Staff ESP colour.");
    public final ColorSetting vanishColor = color("VanishColor", 0xFFB46CFF, "Vanish / ghost marker colour.");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    public static final int PROBE_ID = 0x5747A1;     // our own suggestion request id, so the chat box never sees the answers
    private static final long MESSAGE_WINDOW = 2500; // a join/leave message this close to the tab change counts
    private static final long LISTED_TRUST = 1500;   // a tab entry must have been there this long before its leave counts
    private static final int MASS_CHANGE = 4;        // this many tab changes in one tick = server/shard switch, not vanish

    /** DonutSMP staff accounts (2026). */
    public static final List<String> DONUT_STAFF = List.of(
        "FluffyMaster07", "archivePedro", "Munkerlich", "Frenk_Btw", "Napooo_", "auzzitech", "CryptoDaveYT", "W1zox_",
        "ZEEF69", "showered", "CaptainMoose35", "BobIsFound", "NoahvdAa", "0GSummer", "LzouzMP5", "Pastagamer08", "u_vv",
        "Owen1212055", "Splaterd", "Fallerfly", "Bloodspulse");

    private record Seen(UUID id, String name, Vec3 pos, long time, boolean dying) {}
    private record Marker(Vec3 pos, String label, int color, long time) {}
    private record ChatSender(UUID id, long time) {}
    private static final Pattern VANISH_TAG = Pattern.compile("(?i)\\bvanish(ed)?\\b|\\[v]|\\(v\\)");
    private record TabChange(UUID id, String name, GameProfile profile, Vec3 pos, long time) {}
    private record ChatLine(String text, long time) {}

    private final Map<UUID, Seen> seen = new HashMap<>();           // last position of every loaded player
    private final Map<UUID, GameType> modes = new HashMap<>();
    private final Map<UUID, PlayerInfo> tab = new HashMap<>();             // listed tab entries last tick
    private final Map<UUID, PlayerInfo> connected = new HashMap<>();       // every player info, listed or not
    private final Set<UUID> everConnected = new HashSet<>();               // uuids that were a real connected player this session (not NPCs)
    private final Map<UUID, String> staffWhy = new HashMap<>();     // staff online right now -> reason
    private final Map<UUID, Integer> lookTicks = new HashMap<>();
    private final Set<UUID> near = new HashSet<>();
    private final Set<UUID> wasSpectator = new HashSet<>();
    private final Set<String> vanishers = new HashSet<>();          // lower-case names caught vanishing (= staff)
    private final Map<String, String> hidden = new HashMap<>();     // lower-case -> real name, online but hidden (probe)
    private final Map<String, Map<String, String>> hiddenBy = new HashMap<>(); // probe command -> its last hidden names
    private final Map<String, Integer> probeMisses = new HashMap<>(); // probe command -> answers in a row with no names
    private final Queue<ChatSender> incomingSenders = new ConcurrentLinkedQueue<>();
    private final Queue<Integer> incomingRemoved = new ConcurrentLinkedQueue<>();
    private final Map<Integer, UUID> entityIds = new HashMap<>();   // loaded player entity id -> uuid
    private final Deque<Marker> markerList = new ArrayDeque<>();
    private String probeSent;
    private int probeIndex;
    private boolean logged;
    private final Map<String, Long> lastPop = new HashMap<>();
    private final List<String> pendingJoin = new ArrayList<>();
    private final List<TabChange> leaves = new ArrayList<>(), joins = new ArrayList<>();
    private final Deque<ChatLine> chat = new ArrayDeque<>();
    private final Queue<ChatLine> incomingChat = new ConcurrentLinkedQueue<>();
    private final Queue<List<String>> probeAnswers = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Long> listedSince = new HashMap<>();
    private record Ghost(Vec3 pos, String what, int stateId, long time) {}
    private final Queue<Ghost> incomingGhosts = new ConcurrentLinkedQueue<>();
    private final Deque<Ghost> ghosts = new ArrayDeque<>();            // accepted ghost events, newest last
    private final Deque<Ghost> explosions = new ArrayDeque<>();
    private volatile long lastSelfAction;
    /** Ghost events accepted (dev test). */
    public int ghostEvents;
    private ClientPacketListener connection;
    private Object level;
    private boolean serverShowsLeaves, serverShowsJoins, watched;
    private int joinTicks;
    private long lastProbe;
    /** Alerts fired since the module was turned on (dev test reads these). */
    public int pops;
    public String lastPopName = "", lastPopWhat = "";
    public int probeReplies;

    public StaffWatcher() {
        super("StaffWatcher", "Alerts you when staff are watching you (totem pop of their head) and catches vanished staff.", Category.CLIENT);
    }

    @Override public void onEnable() { reset(); pops = 0; }

    private void reset() {
        seen.clear(); modes.clear(); tab.clear(); connected.clear(); staffWhy.clear(); lookTicks.clear(); near.clear();
        wasSpectator.clear(); vanishers.clear(); hidden.clear(); lastPop.clear(); pendingJoin.clear();
        leaves.clear(); joins.clear(); chat.clear(); incomingChat.clear();
        probeAnswers.clear();
        serverShowsLeaves = serverShowsJoins = watched = false;
        joinTicks = 0;
        lastProbe = 0;
        listedSince.clear();
        incomingGhosts.clear(); ghosts.clear(); explosions.clear();
        hiddenBy.clear(); probeMisses.clear(); incomingSenders.clear(); incomingRemoved.clear(); entityIds.clear(); everConnected.clear();
        markerList.clear();
        probeSent = null;
        logged = false;
    }

    /** New shard/server behind the same connection: the tab list is rebuilt, so start the comparisons over. */
    private void rebase() {
        seen.clear(); modes.clear(); tab.clear(); connected.clear(); lookTicks.clear(); near.clear(); pendingJoin.clear();
        leaves.clear(); joins.clear(); listedSince.clear(); entityIds.clear(); incomingRemoved.clear();
        joinTicks = 0;
    }

    @Override
    public String getInfo() {
        int n = staffWhy.size() + hidden.size();
        if (n == 0) return null;
        return watched ? n + " §cwatched" : String.valueOf(n);
    }

    // ---- who counts as staff ---------------------------------------------------------------

    public boolean onList(String name) {
        for (String s : staff.get().split(","))
            if (s.trim().equalsIgnoreCase(name)) return true;
        if (donutStaff.get()) for (String s : DONUT_STAFF) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Why this player counts as staff, or null. */
    private String reason(PlayerInfo info) {
        UUID id = info.getProfile().id();
        String name = info.getProfile().name();
        if (onList(name)) return "staff list";
        if (ranks.get()) {
            String rank = rankWord(info, name);
            if (rank != null) return rank;
        }
        if (spectators.get()) {
            // sticky: someone who went into spectator and came back is still staff
            if (info.getGameMode() == GameType.SPECTATOR) wasSpectator.add(id);
            if (wasSpectator.contains(id)) return "spectator";
        }
        if (vanishers.contains(name.toLowerCase(Locale.ROOT))) return "vanish";
        if (antiVanish.get() && vanishTags.get() && vanishTagged(info)) return "vanish tag";
        return null;
    }

    /** "[V]", "vanished" in the tab name, the team prefix/suffix or the team's own name. */
    private static boolean vanishTagged(PlayerInfo info) {
        StringBuilder t = new StringBuilder();
        if (info.getTabListDisplayName() != null) t.append(plain(info.getTabListDisplayName().getString())).append(' ');
        if (info.getTeam() != null) {
            t.append(plain(info.getTeam().getPlayerPrefix().getString())).append(' ')
             .append(plain(info.getTeam().getPlayerSuffix().getString())).append(' ').append(info.getTeam().getName());
        }
        return VANISH_TAG.matcher(t).find();
    }

    /** First staff word in the tab-list name (minus the player's own name) or the team prefix/suffix. */
    private String rankWord(PlayerInfo info, String name) {
        StringBuilder text = new StringBuilder();
        Component display = info.getTabListDisplayName();
        if (display != null) text.append(plain(display.getString()).replaceAll("(?i)" + Pattern.quote(name), " ")).append(' ');
        if (info.getTeam() != null) {
            text.append(plain(info.getTeam().getPlayerPrefix().getString())).append(' ');
            text.append(plain(info.getTeam().getPlayerSuffix().getString()));
        }
        Set<String> words = new HashSet<>();
        for (String w : rankWords.get().split(",")) if (!w.isBlank()) words.add(w.trim().toLowerCase(Locale.ROOT));
        for (String token : text.toString().toLowerCase(Locale.ROOT).split("[^a-z]+"))
            if (!token.isEmpty() && words.contains(token)) return token;
        String markers = rankMarkers.get();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp > ' ' && markers.indexOf(cp) >= 0) return "rank " + new String(Character.toChars(cp));
            i += Character.charCount(cp);
        }
        return null;
    }

    private static String plain(String s) {
        String stripped = ChatFormatting.stripFormatting(s);
        return stripped == null ? "" : stripped;
    }

    // ---- packets (netty thread: only hand data over) -------------------------------------------

    @Override
    public void onPacketReceive(PacketEvent e) {
        if (e.packet instanceof ClientboundSystemChatPacket p && !p.overlay()) {
            incomingChat.add(new ChatLine(plain(p.content().getString()).toLowerCase(Locale.ROOT), System.currentTimeMillis()));
        } else if (e.packet instanceof ClientboundSoundPacket p) {
            String what = ghostSound(p.getSound().value().location().getPath());
            if (what != null) incomingGhosts.add(new Ghost(new Vec3(p.getX(), p.getY(), p.getZ()), what, -1, System.currentTimeMillis()));
        } else if (e.packet instanceof ClientboundLevelEventPacket p && p.getType() == 2001) {
            // block destroyed (particles + sound); data = the broken block's state id
            incomingGhosts.add(new Ghost(net.minecraft.world.phys.Vec3.atCenterOf(p.getPos()), "block broken", p.getData(), System.currentTimeMillis()));
        } else if (e.packet instanceof ClientboundExplodePacket p) {
            incomingGhosts.add(new Ghost(p.center(), "explosion", -1, System.currentTimeMillis()));
        } else if (e.packet instanceof ClientboundPlayerChatPacket p) {
            incomingSenders.add(new ChatSender(p.sender(), System.currentTimeMillis()));
        } else if (e.packet instanceof ClientboundRemoveEntitiesPacket p) {
            for (int id : p.entityIds()) incomingRemoved.add(id);
        } else if (e.packet instanceof ClientboundCommandSuggestionsPacket p && p.id() == PROBE_ID) {
            probeAnswers.add(p.suggestions().stream().map(ClientboundCommandSuggestionsPacket.Entry::text).toList());
            e.cancel();
        }
    }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (e.packet instanceof ServerboundUseItemOnPacket || e.packet instanceof ServerboundPlayerActionPacket
            || e.packet instanceof ServerboundAttackPacket || e.packet instanceof ServerboundInteractPacket)
            lastSelfAction = System.currentTimeMillis();
    }

    /** What a sound means if nobody visible made it, or null for sounds that don't point at a player. */
    private static String ghostSound(String path) {
        if (path.endsWith("chest.open") || path.endsWith("barrel.open") || path.endsWith("shulker_box.open")) return "container opened";
        if (path.endsWith("chest.close") || path.endsWith("barrel.close") || path.endsWith("shulker_box.close")) return "container closed";
        if (path.contains("door.open") || path.contains("door.close") || path.contains("fence_gate.")) return "door used";
        if (path.startsWith("block.") && path.endsWith(".place")) return "block placed";
        if (path.startsWith("entity.player.") && !path.contains("levelup")) return "player sound";
        return null;
    }

    // ---- every tick ------------------------------------------------------------------------------

    @Override
    public void onTick() {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        if (conn != connection) { connection = conn; level = mc.level; reset(); } // new server: start over
        if (mc.level != level) { level = mc.level; rebase(); }                 // shard/world switch behind a proxy
        joinTicks++;
        long now = System.currentTimeMillis();
        for (ChatLine c; (c = incomingChat.poll()) != null; ) chat.addLast(c);
        while (!chat.isEmpty() && now - chat.peekFirst().time() > 10_000) chat.removeFirst();

        // bodies removed since last tick, judged by where they were (seen still holds last tick's positions)
        List<Integer> removed = new ArrayList<>();
        for (Integer id; (id = incomingRemoved.poll()) != null; ) removed.add(id);
        for (Player p : mc.level.players())
            if (p != mc.player) {
                seen.put(p.getUUID(), new Seen(p.getUUID(), p.getGameProfile().name(), p.position(), now, p.isDeadOrDying()));
                entityIds.put(p.getId(), p.getUUID());
            }

        scanTab(conn, now);
        if (antiVanish.get()) {
            if (ghostSense.get()) checkGhosts(now);
            else incomingGhosts.clear();
            if (leaveCheck.get()) checkTabChanges(now);
            if (probe.get()) runProbe(conn, now);
            if (chatCheck.get()) checkChatSenders(conn);
            else incomingSenders.clear();
            if (despawnCheck.get()) checkDespawns(conn, removed, now);
        } else incomingSenders.clear();
        for (int id : removed) entityIds.remove(id);
        scanPlayers(conn, now);
        while (!markerList.isEmpty() && now - markerList.peekFirst().time() > markerTime.get() * 1000) markerList.removeFirst();
    }

    /** ChatCheck: signed chat names its sender; connected but unlisted = hidden from TAB while talking. */
    private void checkChatSenders(ClientPacketListener conn) {
        for (ChatSender c; (c = incomingSenders.poll()) != null; ) {
            if (c.id().equals(mc.player.getUUID()) || tab.containsKey(c.id())) continue;
            PlayerInfo info = conn.getPlayerInfo(c.id());
            if (info == null) continue; // the game itself refuses chat from unknown players
            String name = info.getProfile().name();
            vanishers.add(name.toLowerCase(Locale.ROOT));
            alert("Vanish", name, "vanish", ResolvableProfile.createResolved(info.getProfile()), "is chatting while hidden from the tab list", Double.MAX_VALUE);
        }
    }

    /**
     * DespawnCheck: a staff body removed well inside tracking range while its owner stays connected, alive and not in
     * spectator (that one has its own alert) went invisible to us: vanish, or a teleport (/tp, /home).
     */
    private void checkDespawns(ClientPacketListener conn, List<Integer> removed, long now) {
        for (int eid : removed) {
            UUID id = entityIds.get(eid);
            if (id == null) continue;
            Seen s = seen.get(id);
            if (s == null || s.dying() || now - s.time() > 1500) continue;
            if (isNpc(id, s.name())) continue;
            PlayerInfo info = conn.getPlayerInfo(id);
            if (info == null || info.getGameMode() == GameType.SPECTATOR || mc.level.getPlayerByUUID(id) != null) continue;
            double d = s.pos().distanceTo(mc.player.position());
            if (d > despawnRange.get()) continue;
            String why = staffWhy.get(id);
            if (why == null && onList(s.name())) why = "staff list";
            if (why == null) continue;
            addMarker(s.pos(), s.name() + " vanished", vanishColor.color());
            alert("Vanish", s.name(), why, ResolvableProfile.createResolved(info.getProfile()), "disappeared right next to you (vanish or teleport)", d);
        }
    }

    private void addMarker(Vec3 pos, String label, int color) {
        if (pos == null) return;
        markerList.removeIf(m -> m.pos().distanceTo(pos) < 1.5 && m.label().equals(label));
        markerList.addLast(new Marker(pos, label, color, System.currentTimeMillis()));
        while (markerList.size() > 64) markerList.removeFirst();
    }

    /** Markers placed so far (dev test). */
    public int markerCount() { return markerList.size(); }

    /** Tab list: who is staff, joins/leaves, spectator switches. */
    private void scanTab(ClientPacketListener conn, long now) {
        UUID self = mc.player.getUUID();
        Map<UUID, PlayerInfo> current = new HashMap<>();
        for (PlayerInfo info : conn.getOnlinePlayers())
            if (!info.getProfile().id().equals(self)) current.put(info.getProfile().id(), info);
        Map<UUID, PlayerInfo> listed = new HashMap<>();
        for (PlayerInfo info : conn.getListedOnlinePlayers())
            if (!info.getProfile().id().equals(self)) listed.put(info.getProfile().id(), info);

        // Leaves/joins of the LISTED tab, for the vanish checks. A vanish plugin can either drop the player
        // entirely or keep them connected but unlisted (hidden from TAB) - both show up here as a leave.
        // Not in the first ticks (the list is still filling in), and a burst of changes is a server switch.
        if (joinTicks > 40) {
            List<TabChange> left = new ArrayList<>(), came = new ArrayList<>();
            for (var old : tab.entrySet()) {
                if (listed.containsKey(old.getKey())) continue;
                Long since = listedSince.get(old.getKey());
                if (since == null || now - since < LISTED_TRUST) continue; // flickering entries (NPCs, tab plugins)
                Seen s = seen.get(old.getKey());
                Vec3 pos = s != null && now - s.time() < 3000 ? s.pos() : null;
                left.add(new TabChange(old.getKey(), old.getValue().getProfile().name(), old.getValue().getProfile(), pos, now));
            }
            for (var neu : listed.entrySet())
                if (!tab.containsKey(neu.getKey()))
                    came.add(new TabChange(neu.getKey(), neu.getValue().getProfile().name(), neu.getValue().getProfile(), null, now));
            if (left.size() < MASS_CHANGE) leaves.addAll(left);
            if (came.size() < MASS_CHANGE) joins.addAll(came);
        }
        for (UUID id : listed.keySet()) listedSince.putIfAbsent(id, now);
        listedSince.keySet().retainAll(listed.keySet());
        tab.clear();
        tab.putAll(listed);
        connected.clear();
        connected.putAll(current);
        everConnected.addAll(current.keySet());

        List<String> joined = new ArrayList<>();
        Map<UUID, String> nowStaff = new HashMap<>();
        for (PlayerInfo info : current.values()) {
            UUID id = info.getProfile().id();
            String name = info.getProfile().name();
            GameType mode = info.getGameMode();
            GameType before = modes.put(id, mode);
            if (listed.containsKey(id)) hidden.remove(name.toLowerCase(Locale.ROOT)); // visible again
            String why = reason(info);
            if (why == null) continue;
            nowStaff.put(id, why);
            if (!staffWhy.containsKey(id)) joined.add(name + " §7(" + why + ")§f");

            if (mode == GameType.SPECTATOR && before != null && before != GameType.SPECTATOR) {
                // their entity disappears now; if it was right next to us, they're most likely watching us
                Seen s = seen.get(id);
                double d = s == null || now - s.time() > 3000 ? Double.MAX_VALUE : s.pos().distanceTo(mc.player.position());
                if (s != null && now - s.time() <= 3000) addMarker(s.pos(), name + " went spectator", vanishColor.color());
                if (d <= range.get()) alert("Spectator", name, why, ResolvableProfile.createResolved(info.getProfile()), "went into spectator next to you", d);
                else if (onlineAlert.get() && joinTicks > 40) ChatUtil.info("§f" + name + "§7 switched to spectator");
            }
        }
        Set<UUID> staffBefore = new HashSet<>(staffWhy.keySet());
        staffWhy.clear();
        staffWhy.putAll(nowStaff);
        modes.keySet().retainAll(current.keySet());

        // the tab list fills in over the first ticks after joining: those go into one "online" message
        if (joinTicks <= 40) {
            pendingJoin.addAll(joined);
            if (joinTicks == 40 && !pendingJoin.isEmpty() && onlineAlert.get())
                ChatUtil.info("Staff online: §f" + String.join(", ", pendingJoin));
        } else if (!joined.isEmpty()) {
            if (onlineAlert.get()) ChatUtil.info("Staff joined: §f" + String.join(", ", joined));
            for (UUID id : nowStaff.keySet())
                if (!staffBefore.contains(id)) autoLogFire("Joined", current.get(id).getProfile().name(), "joined");
        }
    }

    /** A fake player (ZNPC / Citizens / mannequin), not a real account: never flagged as staff or a vanish. */
    private boolean isNpc(UUID id, String name) {
        if (!ignoreNpcs.get()) return false;
        if (onList(name)) return false; // a name you vouched for is never treated as an NPC
        if (id.version() == 2) return true; // Citizens NPCs use version-2 UUIDs
        for (String n : npcNames.get().split(",")) {
            String t = n.trim();
            if (t.isEmpty()) continue;
            String low = name.toLowerCase(Locale.ROOT), pat = t.toLowerCase(Locale.ROOT);
            if (pat.startsWith("*") && low.endsWith(pat.substring(1))) return true;
            if (pat.endsWith("*") && low.startsWith(pat.substring(0, pat.length() - 1))) return true;
            if (low.equals(pat)) return true;
        }
        // a body in the world whose uuid was never a real connected player is a fake entity, not a hidden staffer
        return !everConnected.contains(id) && !connected.containsKey(id);
    }

    private boolean chatMentions(String name, long time) {
        String n = name.toLowerCase(Locale.ROOT);
        Pattern word = Pattern.compile("(^|[^a-z0-9_])" + Pattern.quote(n) + "($|[^a-z0-9_])");
        for (ChatLine c : chat)
            if (Math.abs(c.time() - time) <= MESSAGE_WINDOW && word.matcher(c.text()).find()) return true;
        return false;
    }

    /** Leave/join check: decided MESSAGE_WINDOW after the tab change, when any message has had time to arrive. */
    private void checkTabChanges(long now) {
        for (Iterator<TabChange> it = leaves.iterator(); it.hasNext(); ) {
            TabChange l = it.next();
            if (now - l.time() < MESSAGE_WINDOW) continue;
            it.remove();
            if (listedSince.containsKey(l.id())) continue;                 // back in the tab already
            if (chatMentions(l.name(), l.time())) { serverShowsLeaves = true; continue; }
            double d = l.pos() == null ? Double.MAX_VALUE : l.pos().distanceTo(mc.player.position());
            boolean close = d <= range.get();
            boolean stillConnected = connected.containsKey(l.id());             // unlisted, not disconnected
            Player body = mc.level.getPlayerByUUID(l.id());
            String what;
            if (stillConnected) what = "is hidden from the tab list but still connected (vanished)";
            else if (body != null && !isNpc(l.id(), l.name())) { what = "left the tab list but is still here (vanished)"; d = body.distanceTo(mc.player); }
            else if (close) what = "vanished next to you";
            else if (serverShowsLeaves) what = "vanished (no leave message)";
            else continue; // no leave messages on this server and nothing else to go on: can't tell
            vanishers.add(l.name().toLowerCase(Locale.ROOT));
            addMarker(body != null ? body.position() : l.pos(), l.name() + " vanished", vanishColor.color());
            alert("Vanish", l.name(), "vanish", ResolvableProfile.createResolved(l.profile()), what, d);
        }
        for (Iterator<TabChange> it = joins.iterator(); it.hasNext(); ) {
            TabChange j = it.next();
            if (now - j.time() < MESSAGE_WINDOW) continue;
            it.remove();
            if (!listedSince.containsKey(j.id())) continue;                // gone again
            if (chatMentions(j.name(), j.time())) { serverShowsJoins = true; continue; }
            if (vanishers.contains(j.name().toLowerCase(Locale.ROOT))) {
                ChatUtil.info("§f" + j.name() + "§7 is visible again");
                continue;
            }
            if (!serverShowsJoins) continue;
            vanishers.add(j.name().toLowerCase(Locale.ROOT));
            ChatUtil.info("§f" + j.name() + "§7 appeared without a join message (unvanished)");
        }
    }

    /** Probe: names the server completes for ProbeCommand that the tab list doesn't have are online but hidden. */
    private void runProbe(ClientPacketListener conn, long now) {
        for (List<String> answer; (answer = probeAnswers.poll()) != null; ) {
            probeReplies++;
            String cmd = probeSent == null ? "" : probeSent;
            Set<String> visible = new HashSet<>();
            for (PlayerInfo i : conn.getListedOnlinePlayers()) visible.add(i.getProfile().name().toLowerCase(Locale.ROOT));
            Map<String, String> nowHidden = new HashMap<>();
            int names = 0;
            for (String s : answer) {
                if (!NAME.matcher(s).matches()) continue; // @a, @p, coordinates...
                names++;
                String low = s.toLowerCase(Locale.ROOT);
                if (!visible.contains(low)) nowHidden.put(low, s);
            }
            // a command that never completes names is blocked or isn't a player command: stop asking it
            if (names == 0) probeMisses.merge(cmd, 1, Integer::sum); else probeMisses.remove(cmd);
            // Many hidden names = the command completes players on other servers of the network (proxy), not
            // vanish: then only names known to be staff count.
            if (nowHidden.size() >= MASS_CHANGE) nowHidden.values().removeIf(n -> !onList(n));
            for (var e : nowHidden.entrySet()) {
                vanishers.add(e.getKey());
                if (!hidden.containsKey(e.getKey()))
                    alert("Vanish", e.getValue(), "vanish", ResolvableProfile.createUnresolved(e.getValue()), "is online but vanished", Double.MAX_VALUE);
            }
            // hidden = what every probe command last said (a plugin's command may hide what minecraft:msg shows)
            hiddenBy.put(cmd, nowHidden);
            hidden.clear();
            for (Map<String, String> h : hiddenBy.values()) hidden.putAll(h);
            hidden.keySet().removeAll(visible);
        }
        if (joinTicks > 100 && now - lastProbe >= probeInterval.get() * 1000) {
            lastProbe = now;
            probeSent = nextProbe();
            if (probeSent != null) conn.send(new ServerboundCommandSuggestionPacket(PROBE_ID, "/" + probeSent + " "));
        }
    }

    /** Next ProbeCommand in turn, skipping ones that answered 3 times in a row without a single name. */
    private String nextProbe() {
        List<String> cmds = new ArrayList<>();
        for (String c : probeCommand.get().split(",")) if (!c.isBlank()) cmds.add(c.trim().replaceFirst("^/", ""));
        if (cmds.isEmpty()) return null;
        for (int i = 0; i < cmds.size(); i++) {
            String c = cmds.get((probeIndex + i) % cmds.size());
            if (probeMisses.getOrDefault(c, 0) < 3) { probeIndex = (probeIndex + i + 1) % cmds.size(); return c; }
        }
        probeMisses.clear(); // all look dead: start over (permissions change, a staff member logs in...)
        return cmds.get(0);
    }

    /**
     * GhostSense: a container/door/block/player sound or a block break where no visible player or mob could have
     * done it, not from us, not redstone, not an explosion, not a piston farm. The first one is just noted in chat;
     * a second within 15 s and 12 blocks of it is the alert. Named when exactly one hidden staff member is known.
     */
    private void checkGhosts(long now) {
        for (Ghost g; (g = incomingGhosts.poll()) != null; ) {
            if (g.what().equals("explosion")) { explosions.addLast(g); continue; }
            if (!ghostCredible(g, now)) continue;
            // open + close of one chest is one event
            Ghost last = ghosts.peekLast();
            if (last != null && last.pos().distanceTo(g.pos()) < 1.5 && g.time() - last.time() < 3000) continue;
            ghostEvents++;
            Ghost partner = null;
            for (Ghost o : ghosts) if (g.time() - o.time() < 15_000 && o.pos().distanceTo(g.pos()) <= 12) partner = o;
            ghosts.addLast(g);
            double d = g.pos().distanceTo(mc.player.position());
            if (partner == null) {
                ChatUtil.info("Ghost: §f" + g.what() + "§7 with nobody visible, " + Math.round(d) + "m " + compass(g.pos()));
                continue;
            }
            String suspect = hiddenSuspect();
            String text = "is hidden near you (" + partner.what() + ", " + g.what() + ", " + compass(g.pos()) + ")";
            addMarker(g.pos(), "ghost: " + g.what(), vanishColor.color());
            if (suspect != null) alertKeyed("Ghost", "ghost:" + suspect, suspect, "ghost", ResolvableProfile.createUnresolved(suspect), text, d);
            else alertKeyed("Ghost", "ghost", "Someone", "ghost", null, text, d);
        }
        while (!ghosts.isEmpty() && now - ghosts.peekFirst().time() > 20_000) ghosts.removeFirst();
        while (!explosions.isEmpty() && now - explosions.peekFirst().time() > 3000) explosions.removeFirst();
    }

    private boolean ghostCredible(Ghost g, long now) {
        Vec3 p = g.pos();
        double d = p.distanceTo(mc.player.position());
        if (d > ghostRange.get()) return false;
        if (now - lastSelfAction < 1000 && d < 7) return false; // our own doing
        for (Ghost x : explosions) if (x.pos().distanceTo(p) < 10) return false;
        // anything visible that could have done it: players, villagers, mobs (endermen, sheep eating grass, golems)
        for (Entity e : mc.level.getEntities(mc.player, new AABB(p, p).inflate(5)))
            if (e instanceof LivingEntity && !e.isInvisible()) return false;
        BlockPos pos = BlockPos.containing(p);
        if (mc.level.getBlockState(pos).getOptionalValue(BlockStateProperties.POWERED).orElse(false)) return false; // redstone door
        if (g.stateId() >= 0) {
            BlockState broken = Block.stateById(g.stateId());
            if (broken.isAir() || broken.getCollisionShape(mc.level, pos).isEmpty()) return false; // crops, cane, grass, torches
            for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
                BlockState st = mc.level.getBlockState(q);
                if (st.is(Blocks.PISTON) || st.is(Blocks.STICKY_PISTON) || st.is(Blocks.MOVING_PISTON) || st.is(Blocks.OBSERVER)) return false; // farms
            }
        }
        return true;
    }

    /** The one hidden staff member we know of, if there is exactly one. */
    private String hiddenSuspect() {
        Set<String> names = new HashSet<>(hidden.values());
        for (var e : connected.entrySet())
            if (!tab.containsKey(e.getKey()) && staffWhy.containsKey(e.getKey())) names.add(e.getValue().getProfile().name());
        return names.size() == 1 ? names.iterator().next() : null;
    }

    private String compass(Vec3 to) {
        double dx = to.x - mc.player.getX(), dz = to.z - mc.player.getZ();
        String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        double deg = (Math.toDegrees(Math.atan2(-dx, dz)) + 360 + 22.5) % 360;
        return dirs[(int) (deg / 45)];
    }

    /** Loaded staff players: looking at us / close by. */
    private void scanPlayers(ClientPacketListener conn, long now) {
        watched = false;
        int needTicks = Math.max(1, (int) Math.round(lookTime.get() * 20));
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            UUID id = p.getUUID();
            String name = p.getGameProfile().name();
            if (isNpc(id, name)) { lookTicks.remove(id); near.remove(id); continue; }
            String why = staffWhy.get(id);
            if (why == null && conn.getPlayerInfo(id) == null && onList(name)) why = "staff list"; // not in the tab list, but named
            if (why == null) { lookTicks.remove(id); near.remove(id); continue; }
            double d = p.distanceTo(mc.player);

            if (antiVanish.get() && p.isInvisible() && d <= range.get())
                alert("Invisible", name, why, ResolvableProfile.createResolved(p.getGameProfile()), "is invisible near you", d);

            if (d <= range.get() && looksAtMe(p, d)) {
                watched = true;
                int t = lookTicks.merge(id, 1, Integer::sum);
                if (t == needTicks) alert("Watching", name, why, ResolvableProfile.createResolved(p.getGameProfile()), "is looking at you", d);
            } else lookTicks.remove(id);

            if (nearAlert.get()) {
                if (!near.contains(id) && d <= nearRange.get()) {
                    near.add(id);
                    ChatUtil.info("§f" + name + " §7(" + why + ") is near you §f" + Math.round(d) + "m");
                    autoLogFire("Near", name, "is near you");
                } else if (near.contains(id) && d > nearRange.get() + 4) near.remove(id);
            }
        }
    }

    /** Their look ray hits our hitbox, grown by the LookAngle cone at this distance. */
    private boolean looksAtMe(Player p, double d) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(p.getXRot(), p.getYHeadRot());
        double grow = Math.tan(Math.toRadians(lookAngle.get())) * d;
        AABB box = mc.player.getBoundingBox().inflate(grow);
        if (box.clip(eye, eye.add(look.scale(range.get() + 8))).isEmpty() && !box.contains(eye)) return false;
        if (throughWalls.get()) return true;
        return mc.level.clip(new ClipContext(eye, mc.player.getEyePosition(), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p))
            .getType() == HitResult.Type.MISS;
    }

    /** Chat + head pop + sound, once per Cooldown per person. */
    private void alert(String kind, String name, String why, ResolvableProfile head, String what, double d) {
        alertKeyed(kind, name, name, why, head, what, d);
    }

    private void alertKeyed(String kind, String cooldownKey, String name, String why, ResolvableProfile head, String what, double d) {
        long now = System.currentTimeMillis();
        autoLogFire(kind, name, what); // before the cooldown: a repeat while AutoLog is armed still counts
        String key = cooldownKey.toLowerCase(Locale.ROOT);
        Long last = lastPop.get(key);
        if (last != null && now - last < cooldown.get() * 1000) return;
        lastPop.put(key, now);
        pops++;
        lastPopName = name;
        lastPopWhat = what;
        String dist = d == Double.MAX_VALUE ? "" : " §7(" + Math.round(d) + "m)";
        ChatUtil.error("§f" + name + " §7(" + why + ")§c " + what + dist);

        // StreamProof hides Prism from capture: the pop is drawn in the game frame, so it stays off then
        if (headPop.get() && !dev.prismglass.streamproof.Overlay.active()) {
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            if (head != null) stack.set(DataComponents.PROFILE, head);
            mc.player.displayItemActivation(stack);
            if (particles.get()) mc.particleEngine.createTrackingEmitter(mc.player, ParticleTypes.TOTEM_OF_UNDYING, 30);
        }
        Vec3 at = mc.player.position();
        if (sound.is("Ping"))
            mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1f, 1.6f, false);
        else if (sound.is("Totem"))
            mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.TOTEM_USE, SoundSource.MASTER, 1f, 1f, false);
    }

    /** AutoLog: this alert kind is in LogOn -> disconnect and/or panic, once per connection. */
    private void autoLogFire(String kind, String name, String what) {
        if (autoLog.is("Off") || logged || !logOn.items().contains(kind)) return;
        if (logListedOnly.get() && (name == null || !onList(name))) return;
        logged = true;
        logFired++;
        String reason = name + " " + plain(what);
        boolean panic = autoLog.is("Panic") || autoLog.is("Both"), leave = autoLog.is("Disconnect") || autoLog.is("Both");
        if (logOnce.get()) autoLog.parse("Off");
        if (panic) {
            KeyActions.panic(this);
            ChatUtil.error("StaffWatcher AutoLog: panic, " + reason);
        }
        if (leave && mc.getConnection() != null && !dryRunLog) {
            AutoReconnect.blockOnce();
            mc.getConnection().getConnection().disconnect(Component.literal("[Prism StaffWatcher] " + reason));
        }
    }

    /** AutoLog fires so far, and a dev switch that skips the real disconnect (tests). */
    public int logFired;
    public boolean dryRunLog;

    // ---- visuals ---------------------------------------------------------------------------------

    private boolean isStaff(Player p) {
        return staffWhy.containsKey(p.getUUID()) || onList(p.getGameProfile().name());
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        if (staffEsp.get()) {
            for (Player p : mc.level.players()) {
                if (p == mc.player || !isStaff(p)) continue;
                boolean looking = lookTicks.containsKey(p.getUUID());
                int c = looking ? 0xFFFF3B3B : staffColor.color();
                Render3D.box(Render3D.lerpBox(p, delta), ColorUtil.withAlpha(c, looking ? 46 : 26), ColorUtil.withAlpha(c, 230), true);
                Vec3 eye = p.getEyePosition(delta);
                Vec3 end = looking ? mc.player.getBoundingBox().getCenter() : eye.add(p.getViewVector(delta).scale(3.5));
                Render3D.line(eye, end, ColorUtil.withAlpha(c, looking ? 220 : 130));
            }
        }
        if (markers.get()) {
            long now = System.currentTimeMillis();
            float pulse = 0.75f + 0.25f * Mth.sin(now / 220f);
            for (Marker m : markerList) {
                float life = 1 - (now - m.time()) / (float) (markerTime.get() * 1000);
                if (life <= 0) continue;
                int a = (int) (255 * Math.min(1, life * 3) * pulse);
                Vec3 p = m.pos();
                Render3D.box(new AABB(p.x - 0.12, p.y, p.z - 0.12, p.x + 0.12, p.y + 28, p.z + 0.12),
                    ColorUtil.withAlpha(m.color(), a * 70 / 255), ColorUtil.withAlpha(m.color(), a * 150 / 255), true);
                double r = 0.4 + 0.5 * ((now - m.time()) % 1600) / 1600.0; // ripple on the ground
                Render3D.box(new AABB(p.x - r, p.y, p.z - r, p.x + r, p.y + 0.03, p.z + r),
                    ColorUtil.withAlpha(m.color(), a * 40 / 255), ColorUtil.withAlpha(m.color(), (int) (a * (1.1 - r))), true);
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (mc.gui.hud.isHidden() && !Hud.editing) return;
        long now = System.currentTimeMillis();
        if (markers.get()) {
            for (Marker m : markerList) {
                float life = 1 - (now - m.time()) / (float) (markerTime.get() * 1000);
                if (life <= 0 || m.pos().distanceTo(mc.player.position()) > 160) continue;
                double[] s = Render3D.project(m.pos().add(0, 2.3, 0));
                if (s == null) continue;
                String text = m.label() + " §7" + (now - m.time()) / 1000 + "s";
                ctx.text(mc.font, text, (int) s[0] - mc.font.width(text) / 2, (int) s[1], ColorUtil.withAlpha(m.color(), (int) (255 * Math.min(1, life * 3))), true);
            }
        }
        if (watchedOverlay.get() && watched) drawWatched(ctx, now);
        if (hud.get()) drawHud(ctx);
    }

    /** Red edge pulse + "WATCHED by" banner. */
    private void drawWatched(GuiGraphicsExtractor ctx, long now) {
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        float pulse = 0.55f + 0.45f * Mth.sin(now / 170f);
        int a = (int) (110 * pulse), rgb = 0xFF2B3B;
        for (int i = 0; i < 14; i++) {
            int c = ColorUtil.withAlpha(rgb, a * (14 - i) / 14 / 2);
            ctx.fill(i * 2, 0, i * 2 + 2, sh, c);
            ctx.fill(sw - i * 2 - 2, 0, sw - i * 2, sh, c);
        }
        ctx.fillGradient(0, 0, sw, 28, ColorUtil.withAlpha(rgb, a), ColorUtil.withAlpha(rgb, 0));
        ctx.fillGradient(0, sh - 28, sw, sh, ColorUtil.withAlpha(rgb, 0), ColorUtil.withAlpha(rgb, a));
        List<String> who = new ArrayList<>();
        for (Player p : mc.level.players()) if (lookTicks.containsKey(p.getUUID())) who.add(p.getGameProfile().name());
        String text = "§c◉ §fWATCHED §7by §f" + (who.isEmpty() ? "staff" : String.join(", ", who));
        int w = mc.font.width(text) + 16;
        Glass.pill(ctx, sw / 2f - w / 2f, 34, w, 14, ColorUtil.withAlpha(0x2A0A10, 200), ColorUtil.withAlpha(0x150508, 200), 0.6f);
        ctx.text(mc.font, text, sw / 2 - w / 2 + 8, 37, 0xFFFFFFFF, true);
    }

    /** Glass panel: staff online with status (watching, hidden, spectator, distance), plus probe-only hidden names. */
    private void drawHud(GuiGraphicsExtractor ctx) {
        List<String> lines = new ArrayList<>();
        for (var e : staffWhy.entrySet()) {
            PlayerInfo info = connected.get(e.getKey());
            if (info == null) continue;
            StringBuilder l = new StringBuilder();
            l.append(lookTicks.containsKey(e.getKey()) ? "§c◉ " : tab.containsKey(e.getKey()) ? "§a● " : "§d◌ ");
            l.append("§6").append(info.getProfile().name());
            if (!tab.containsKey(e.getKey())) l.append(" §d[V]");
            if (info.getGameMode() == GameType.SPECTATOR) l.append(" §8[spec]");
            Player body = mc.level.getPlayerByUUID(e.getKey());
            if (body != null) l.append(" §7").append(Math.round(body.distanceTo(mc.player))).append("m");
            l.append(" §8").append(e.getValue());
            lines.add(l.toString());
        }
        Set<String> shown = new HashSet<>();
        for (UUID id : staffWhy.keySet()) if (connected.get(id) != null) shown.add(connected.get(id).getProfile().name().toLowerCase(Locale.ROOT));
        for (var h : hidden.entrySet()) if (!shown.contains(h.getKey())) lines.add("§d◌ §6" + h.getValue() + " §d[V] §8probe");
        if (lines.isEmpty() && !Hud.editing) return;
        Collections.sort(lines);
        lines.add(0, "§fStaff §7" + lines.size() + (watched ? " §c◉ watched" : ""));
        float w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        w += 12;
        float h = lines.size() * 10 + 6;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();
        Hud hudModule = Prism.modules().get(Hud.class);
        float[] p = hudModule != null && hudModule.isEnabled() ? hudModule.place("Staff", w, h, sw - w - 4, 60, sw, sh) : new float[]{sw - w - 4, 60};
        Glass.panel(ctx, p[0], p[1], w, h, Prism.modules().get(ClickGui.class).panelStyle());
        if (watched) ctx.fill((int) p[0] + 1, (int) p[1] + 2, (int) p[0] + 3, (int) (p[1] + h - 2),
            ColorUtil.withAlpha(0xFF2B3B, (int) (160 + 90 * Mth.sin(System.currentTimeMillis() / 170f))));
        for (int k = 0; k < lines.size(); k++) ctx.text(mc.font, lines.get(k), (int) p[0] + 6, (int) p[1] + 4 + k * 10, 0xFFFFFFFF, true);
    }

    // ---- .staff command --------------------------------------------------------------------------

    /** Staff online right now plus vanished names, with the reason (for .staff list). */
    public List<String> onlineStaff() {
        List<String> out = new ArrayList<>();
        for (var e : staffWhy.entrySet()) {
            PlayerInfo info = connected.get(e.getKey());
            if (info != null) out.add(info.getProfile().name() + (tab.containsKey(e.getKey()) ? "" : " §c[hidden]§f") + " §7(" + e.getValue() + ")§f");
        }
        for (String h : hidden.values()) out.add(h + " §7(vanished)§f");
        Collections.sort(out);
        return out;
    }

    public void addStaff(String name) {
        if (onList(name)) return;
        staff.parse(staff.get().isBlank() ? name : staff.get() + "," + name);
    }

    public void removeStaff(String name) {
        List<String> keep = new ArrayList<>();
        for (String s : staff.get().split(",")) if (!s.isBlank() && !s.trim().equalsIgnoreCase(name)) keep.add(s.trim());
        staff.parse(String.join(",", keep));
    }

    public List<String> staffList() {
        List<String> out = new ArrayList<>();
        for (String s : staff.get().split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }
}
