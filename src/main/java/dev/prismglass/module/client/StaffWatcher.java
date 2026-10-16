package dev.prismglass.module.client;

import com.mojang.authlib.GameProfile;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
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
 * <p>AntiVanish (vanish plugins drop the player from the tab list and the world, so it has to be inferred):
 * <ul>
 *   <li>Leave check: someone leaves the tab list and no chat line with their name shows up. Only trusted once this
 *       server has shown a real leave message, so servers with leave messages off don't flag everyone.</li>
 *   <li>Join check, the same in reverse: someone appears in the tab list with no join message = unvanished.</li>
 *   <li>Probe: asks the server to tab-complete ProbeCommand (default "msg ") every ProbeInterval seconds. Names it
 *       suggests that aren't in the tab list are online but hidden. Works where the command's completion doesn't
 *       filter vanished players (vanilla, many plugin commands); Paper filters vanilla commands, so try others.</li>
 * </ul>
 */
public class StaffWatcher extends Module {
    public final TextSetting staff = text("Staff", "", "Staff names, comma separated (or .staff add <name>).");
    public final BoolSetting ranks = bool("DetectRanks", true, "Count players with a staff word in their tab-list rank or team prefix.");
    public final TextSetting rankWords = text("RankWords",
        "owner,coowner,admin,administrator,headadmin,mod,moderator,jrmod,srmod,trialmod,helper,jrhelper,srhelper,staff,manager,dev,developer,support,sysadmin",
        "Staff words, matched as whole words in prefixes ([Sr.Mod] = sr + mod).").visibleWhen(ranks::get);
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
    public final TextSetting probeCommand = text("ProbeCommand", "msg ", "Command whose completion lists players (no slash).").visibleWhen(() -> antiVanish.get() && probe.get());
    public final NumberSetting probeInterval = num("ProbeInterval", 10, 2, 120, 1, "Seconds between probes.").visibleWhen(() -> antiVanish.get() && probe.get());

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    public static final int PROBE_ID = 0x5747A1;     // our own suggestion request id, so the chat box never sees the answers
    private static final long MESSAGE_WINDOW = 2500; // a join/leave message this close to the tab change counts

    private record Seen(UUID id, String name, Vec3 pos, long time) {}
    private record TabChange(UUID id, String name, GameProfile profile, Vec3 pos, long time) {}
    private record ChatLine(String text, long time) {}

    private final Map<UUID, Seen> seen = new HashMap<>();           // last position of every loaded player
    private final Map<UUID, GameType> modes = new HashMap<>();
    private final Map<UUID, PlayerInfo> tab = new HashMap<>();
    private final Map<UUID, String> staffWhy = new HashMap<>();     // staff online right now -> reason
    private final Map<UUID, Integer> lookTicks = new HashMap<>();
    private final Set<UUID> near = new HashSet<>();
    private final Set<UUID> wasSpectator = new HashSet<>();
    private final Set<String> vanishers = new HashSet<>();          // lower-case names caught vanishing (= staff)
    private final Map<String, String> hidden = new HashMap<>();     // lower-case -> real name, online but hidden (probe)
    private final Map<String, Long> lastPop = new HashMap<>();
    private final List<String> pendingJoin = new ArrayList<>();
    private final List<TabChange> leaves = new ArrayList<>(), joins = new ArrayList<>();
    private final Deque<ChatLine> chat = new ArrayDeque<>();
    private final Queue<ChatLine> incomingChat = new ConcurrentLinkedQueue<>();
    private final Queue<List<String>> probeAnswers = new ConcurrentLinkedQueue<>();
    private ClientPacketListener connection;
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
        seen.clear(); modes.clear(); tab.clear(); staffWhy.clear(); lookTicks.clear(); near.clear();
        wasSpectator.clear(); vanishers.clear(); hidden.clear(); lastPop.clear(); pendingJoin.clear();
        leaves.clear(); joins.clear(); chat.clear(); incomingChat.clear();
        probeAnswers.clear();
        serverShowsLeaves = serverShowsJoins = watched = false;
        joinTicks = 0;
        lastProbe = 0;
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
        return null;
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
        } else if (e.packet instanceof ClientboundCommandSuggestionsPacket p && p.id() == PROBE_ID) {
            probeAnswers.add(p.suggestions().stream().map(ClientboundCommandSuggestionsPacket.Entry::text).toList());
            e.cancel();
        }
    }

    // ---- every tick ------------------------------------------------------------------------------

    @Override
    public void onTick() {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        if (conn != connection) { connection = conn; reset(); } // new server: start over
        joinTicks++;
        long now = System.currentTimeMillis();
        for (ChatLine c; (c = incomingChat.poll()) != null; ) chat.addLast(c);
        while (!chat.isEmpty() && now - chat.peekFirst().time() > 10_000) chat.removeFirst();

        for (Player p : mc.level.players())
            if (p != mc.player) seen.put(p.getUUID(), new Seen(p.getUUID(), p.getGameProfile().name(), p.position(), now));

        scanTab(conn, now);
        if (antiVanish.get()) {
            if (leaveCheck.get()) checkTabChanges(now);
            if (probe.get()) runProbe(conn, now);
        }
        scanPlayers(conn, now);
    }

    /** Tab list: who is staff, joins/leaves, spectator switches. */
    private void scanTab(ClientPacketListener conn, long now) {
        UUID self = mc.player.getUUID();
        Map<UUID, PlayerInfo> current = new HashMap<>();
        for (PlayerInfo info : conn.getOnlinePlayers())
            if (!info.getProfile().id().equals(self)) current.put(info.getProfile().id(), info);

        // tab-list leaves and joins, for the vanish checks (not in the first ticks: the list is still filling in)
        if (joinTicks > 40) {
            for (var old : tab.entrySet()) {
                if (current.containsKey(old.getKey())) continue;
                Seen s = seen.get(old.getKey());
                Vec3 pos = s != null && now - s.time() < 3000 ? s.pos() : null;
                leaves.add(new TabChange(old.getKey(), old.getValue().getProfile().name(), old.getValue().getProfile(), pos, now));
            }
            for (var neu : current.entrySet())
                if (!tab.containsKey(neu.getKey()))
                    joins.add(new TabChange(neu.getKey(), neu.getValue().getProfile().name(), neu.getValue().getProfile(), null, now));
        }
        tab.clear();
        tab.putAll(current);

        List<String> joined = new ArrayList<>();
        Map<UUID, String> nowStaff = new HashMap<>();
        for (PlayerInfo info : current.values()) {
            UUID id = info.getProfile().id();
            String name = info.getProfile().name();
            GameType mode = info.getGameMode();
            GameType before = modes.put(id, mode);
            hidden.remove(name.toLowerCase(Locale.ROOT)); // visible again
            String why = reason(info);
            if (why == null) continue;
            nowStaff.put(id, why);
            if (!staffWhy.containsKey(id)) joined.add(name + " §7(" + why + ")§f");

            if (mode == GameType.SPECTATOR && before != null && before != GameType.SPECTATOR) {
                // their entity disappears now; if it was right next to us, they're most likely watching us
                Seen s = seen.get(id);
                double d = s == null || now - s.time() > 3000 ? Double.MAX_VALUE : s.pos().distanceTo(mc.player.position());
                if (d <= range.get()) alert(name, why, ResolvableProfile.createResolved(info.getProfile()), "went into spectator next to you", d);
                else if (onlineAlert.get() && joinTicks > 40) ChatUtil.info("§f" + name + "§7 switched to spectator");
            }
        }
        staffWhy.clear();
        staffWhy.putAll(nowStaff);
        modes.keySet().retainAll(current.keySet());

        // the tab list fills in over the first ticks after joining: those go into one "online" message
        if (joinTicks <= 40) {
            pendingJoin.addAll(joined);
            if (joinTicks == 40 && !pendingJoin.isEmpty() && onlineAlert.get())
                ChatUtil.info("Staff online: §f" + String.join(", ", pendingJoin));
        } else if (!joined.isEmpty() && onlineAlert.get()) {
            ChatUtil.info("Staff joined: §f" + String.join(", ", joined));
        }
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
            if (chatMentions(l.name(), l.time())) { serverShowsLeaves = true; continue; }
            double d = l.pos() == null ? Double.MAX_VALUE : l.pos().distanceTo(mc.player.position());
            boolean close = d <= range.get();
            if (!serverShowsLeaves && !close) continue; // no leave messages here at all: can't tell
            vanishers.add(l.name().toLowerCase(Locale.ROOT));
            alert(l.name(), "vanish", ResolvableProfile.createResolved(l.profile()), close ? "vanished next to you" : "vanished (no leave message)", d);
        }
        for (Iterator<TabChange> it = joins.iterator(); it.hasNext(); ) {
            TabChange j = it.next();
            if (now - j.time() < MESSAGE_WINDOW) continue;
            it.remove();
            if (chatMentions(j.name(), j.time())) { serverShowsJoins = true; continue; }
            if (!serverShowsJoins) continue;
            vanishers.add(j.name().toLowerCase(Locale.ROOT));
            ChatUtil.info("§f" + j.name() + "§7 appeared without a join message (unvanished)");
        }
    }

    /** Probe: names the server completes for ProbeCommand that the tab list doesn't have are online but hidden. */
    private void runProbe(ClientPacketListener conn, long now) {
        for (List<String> answer; (answer = probeAnswers.poll()) != null; ) {
            probeReplies++;
            Set<String> visible = new HashSet<>();
            for (PlayerInfo i : conn.getOnlinePlayers()) visible.add(i.getProfile().name().toLowerCase(Locale.ROOT));
            Map<String, String> nowHidden = new HashMap<>();
            for (String s : answer) {
                if (!NAME.matcher(s).matches()) continue; // @a, @p, coordinates...
                String low = s.toLowerCase(Locale.ROOT);
                if (visible.contains(low)) continue;
                nowHidden.put(low, s);
                vanishers.add(low);
                if (!hidden.containsKey(low)) alert(s, "vanish", ResolvableProfile.createUnresolved(s), "is online but vanished", Double.MAX_VALUE);
            }
            hidden.clear();
            hidden.putAll(nowHidden);
        }
        if (joinTicks > 100 && now - lastProbe >= probeInterval.get() * 1000) {
            lastProbe = now;
            conn.send(new ServerboundCommandSuggestionPacket(PROBE_ID, "/" + probeCommand.get()));
        }
    }

    /** Loaded staff players: looking at us / close by. */
    private void scanPlayers(ClientPacketListener conn, long now) {
        watched = false;
        int needTicks = Math.max(1, (int) Math.round(lookTime.get() * 20));
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            UUID id = p.getUUID();
            String name = p.getGameProfile().name();
            String why = staffWhy.get(id);
            if (why == null && conn.getPlayerInfo(id) == null && onList(name)) why = "staff list"; // not in the tab list, but named
            if (why == null) { lookTicks.remove(id); near.remove(id); continue; }
            double d = p.distanceTo(mc.player);

            if (d <= range.get() && looksAtMe(p, d)) {
                watched = true;
                int t = lookTicks.merge(id, 1, Integer::sum);
                if (t == needTicks) alert(name, why, ResolvableProfile.createResolved(p.getGameProfile()), "is looking at you", d);
            } else lookTicks.remove(id);

            if (nearAlert.get()) {
                if (!near.contains(id) && d <= nearRange.get()) {
                    near.add(id);
                    ChatUtil.info("§f" + name + " §7(" + why + ") is near you §f" + Math.round(d) + "m");
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
    private void alert(String name, String why, ResolvableProfile head, String what, double d) {
        long now = System.currentTimeMillis();
        String key = name.toLowerCase(Locale.ROOT);
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
            stack.set(DataComponents.PROFILE, head);
            mc.gameRenderer.displayItemActivation(stack);
            if (particles.get()) mc.particleEngine.createTrackingEmitter(mc.player, ParticleTypes.TOTEM_OF_UNDYING, 30);
        }
        Vec3 at = mc.player.position();
        if (sound.is("Ping"))
            mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1f, 1.6f, false);
        else if (sound.is("Totem"))
            mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.TOTEM_USE, SoundSource.MASTER, 1f, 1f, false);
    }

    // ---- .staff command --------------------------------------------------------------------------

    /** Staff online right now plus vanished names, with the reason (for .staff list). */
    public List<String> onlineStaff() {
        List<String> out = new ArrayList<>();
        for (var e : staffWhy.entrySet()) {
            PlayerInfo info = tab.get(e.getKey());
            if (info != null) out.add(info.getProfile().name() + " §7(" + e.getValue() + ")§f");
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
