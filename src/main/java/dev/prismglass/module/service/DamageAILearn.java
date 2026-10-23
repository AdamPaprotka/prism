package dev.prismglass.module.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.prismglass.Prism;
import dev.prismglass.ai.OpponentProfile;
import dev.prismglass.ai.Tactic;
import dev.prismglass.module.Service;
import dev.prismglass.module.movement.AutoWalk;
import dev.prismglass.setting.*;
import dev.prismglass.util.HitboxUtil;
import dev.prismglass.util.RotationUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Damage AI Learn: learns how people fight and how to beat them, and drives KillAura's movement and aim with it
 * (KillAura → UseAI, only shown while this service runs).
 *
 * <p><b>Watching.</b> For everyone fighting near you it measures, per player: the distance they hit you from (their
 * real reach), the ticks between their swings, their sideways and in/out speed around you (strafe habit), how fast
 * their head tracks you, how often their hits are crits, and every exchange of damage (your health, and theirs,
 * which the server syncs). Hits are put on the opponent who swung just before you got hurt.
 *
 * <p><b>Learning.</b> A fight is cut into episodes of a few seconds, each fought with one {@link Tactic}. Its score
 * is damage dealt minus damage taken (times Risk) per second, added to that player's stats and to the "*" profile
 * everyone shares. The next tactic is chosen by Thompson sampling: each tactic's score is drawn from what we know
 * about it (mean and uncertainty, shrunk toward the shared profile while a player is new), the best draw wins. So it
 * keeps trying things it isn't sure about and settles on what works against that player.
 *
 * <p><b>Fighting.</b> The tactic gives KillAura a point to walk to (real WASD through MovementHooks), Kite steps in
 * right after they swing and back out to beyond their measured reach before their next one, and the aim leads them
 * along their movement by your ping (always kept inside the box the server checks, so hits stay legal).
 * Everything is saved to prism/ai/opponents.json, so it keeps getting better against people you meet again.
 */
public class DamageAILearn extends Service {
    public final NumberSetting episode = num("Episode", 3, 1, 10, 0.5, "Seconds each tactic is tried before it's scored.");
    public final NumberSetting explore = num("Explore", 1.0, 0, 3, 0.1, "How much it keeps trying tactics it isn't sure about (0 = always the best so far).");
    public final NumberSetting risk = num("Risk", 1.2, 0.2, 3, 0.1, "How much damage taken counts against damage dealt.");
    public final BoolSetting predict = bool("Predict", true, "Lead your aim along where they're moving (your ping ahead).");
    public final BoolSetting learnMobs = bool("LearnMobs", false, "Also learn from mobs (all mobs of a type share a profile).");
    public final BoolSetting announce = bool("Announce", false, "Chat line with what it learned when a fight ends.");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String EVERYONE = "*";

    private Map<String, OpponentProfile> profiles;
    private final Map<Integer, Live> live = new HashMap<>();
    private final Random random = new Random();
    private long tick;
    private float ourLastHealth = -1;
    private int ourLastHurt;
    private boolean dirty;

    /** A fight going on right now. */
    private static final class Live {
        final int id;
        final String name;
        boolean wasSwinging, falling;
        long lastSwing = -1000, lastOurAttack = -1000, lastSeen, episodeStart;
        float lastHealth = -1;
        float lastHeadYaw = Float.NaN;
        Tactic tactic;
        double dealt, taken;

        Live(int id, String name) { this.id = id; this.name = name; }
    }

    public DamageAILearn() {
        super("Damage AI Learn", "Learns how everyone you fight plays and how to beat them; KillAura's UseAI fights with it.");
    }

    @Override public void onDisable() { for (Live l : live.values()) endFight(l); live.clear(); save(); }

    @Override public void onWorldJoin() { live.clear(); ourLastHealth = -1; }

    // ---- watching ---------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        tick++;
        var me = mc.player;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !learnable(le) || me.distanceTo(e) > 12) continue;
            Live l = live.computeIfAbsent(e.getId(), id -> new Live(id, nameOf(le)));
            l.lastSeen = tick;
            observe(l, le);
        }
        // our health: a drop with a fresh hurt animation is a hit on us
        float hp = me.getHealth() + me.getAbsorptionAmount();
        if (ourLastHealth >= 0 && me.hurtTime > ourLastHurt && hp < ourLastHealth - 0.01f) onHurt(ourLastHealth - hp);
        ourLastHealth = hp;
        ourLastHurt = me.hurtTime;

        long episodeTicks = Math.round(episode.get() * 20);
        for (Iterator<Live> it = live.values().iterator(); it.hasNext(); ) {
            Live l = it.next();
            if (tick - l.lastSeen > 100) { endFight(l); it.remove(); continue; }
            if (l.tactic != null && tick - l.episodeStart >= episodeTicks) {
                score(l);
                l.tactic = choose(l.name); // a new episode
                l.episodeStart = tick;
            }
        }
        if (dirty && tick % 600 == 0) save();
    }

    private boolean learnable(LivingEntity e) {
        if (e == mc.player || !e.isAlive()) return false;
        if (e instanceof Player p) return !p.isSpectator() && !p.isCreative() && !Prism.friends().isFriend(p);
        return learnMobs.get() && e instanceof Enemy;
    }

    private static String nameOf(LivingEntity e) {
        return e instanceof Player p ? p.getGameProfile().name() : e.getType().getDescription().getString();
    }

    private void observe(Live l, LivingEntity e) {
        OpponentProfile p = profile(l.name);
        var me = mc.player;
        // their swings, and the gaps between them
        if (e.swinging && !l.wasSwinging) {
            long gap = tick - l.lastSwing;
            if (gap > 1 && gap < 60 && me.distanceTo(e) < 6) p.swingGap.add(gap);
            l.lastSwing = tick;
            l.falling = !e.onGround() && e.getDeltaMovement().y < 0;
        }
        l.wasSwinging = e.swinging;
        // their health dropping right after we hit them: our damage
        float hp = e.getHealth() + e.getAbsorptionAmount();
        if (l.lastHealth >= 0 && hp < l.lastHealth - 0.01f && tick - l.lastOurAttack <= 6) {
            double d = l.lastHealth - hp;
            l.dealt += d;
            p.damageDealt += d;
            p.hitsLanded++;
            dirty = true;
        }
        l.lastHealth = hp;
        // how they move around us: sideways (clockwise +) and in/out
        double dist = me.distanceTo(e);
        if (dist < 8) {
            Vec3 to = new Vec3(me.getX() - e.getX(), 0, me.getZ() - e.getZ());
            if (to.lengthSqr() > 1e-4) {
                to = to.normalize();
                Vec3 v = new Vec3(e.getX() - e.xo, 0, e.getZ() - e.zo).scale(20); // blocks per second
                Vec3 cw = new Vec3(-to.z, 0, to.x); // clockwise around us, seen from above
                p.strafe.add(v.dot(cw));
                p.approach.add(v.dot(to));
            }
            float yaw = e.getYHeadRot();
            if (!Float.isNaN(l.lastHeadYaw)) p.turnRate.add(Math.abs(Mth.wrapDegrees(yaw - l.lastHeadYaw)));
            l.lastHeadYaw = yaw;
        }
    }

    /** We lost health: blame whoever swung at us just now (else the closest one in reach). */
    private void onHurt(float damage) {
        Live by = null;
        long best = Long.MAX_VALUE;
        for (Live l : live.values()) {
            Entity e = mc.level.getEntity(l.id);
            if (e == null || mc.player.distanceTo(e) > 6) continue;
            long since = tick - l.lastSwing;
            long key = since <= 4 ? since : 100 + Math.round(mc.player.distanceTo(e) * 10);
            if (key < best) { best = key; by = l; }
        }
        if (by == null) return;
        OpponentProfile p = profile(by.name);
        Entity e = mc.level.getEntity(by.id);
        by.taken += damage;
        p.damageTaken += damage;
        p.hitsTaken++;
        if (e != null) p.reach.add(mc.player.distanceTo(e));
        p.critRate.add(by.falling ? 1 : 0);
        dirty = true;
    }

    @Override
    public void onAttack(Entity target) {
        Live l = live.get(target.getId());
        if (l != null) l.lastOurAttack = tick;
    }

    // ---- learning ---------------------------------------------------------------------------------------

    private void score(Live l) {
        double seconds = Math.max(0.5, (tick - l.episodeStart) / 20.0);
        if (l.dealt == 0 && l.taken == 0) { l.dealt = l.taken = 0; return; } // nobody was in range: no evidence
        double reward = (l.dealt - l.taken * risk.get()) / seconds;
        profile(l.name).tactic(l.tactic).add(reward);
        profile(EVERYONE).tactic(l.tactic).add(reward);
        l.dealt = l.taken = 0;
        dirty = true;
    }

    private void endFight(Live l) {
        if (l.tactic != null) score(l);
        OpponentProfile p = profile(l.name);
        if (l.tactic != null) p.fights++;
        if (announce.get() && l.tactic != null && mc.player != null) dev.prismglass.util.ChatUtil.info("AI: " + summary(l.name));
    }

    /**
     * Thompson sampling: draw each tactic's score from what we believe about it, take the best draw. Against an
     * opponent we know little about, every tactic is tried twice first (in random order). A tactic without data is
     * judged against the average of all tactics, with wide uncertainty, so one early good result can't lock it in.
     */
    private Tactic choose(String name) {
        OpponentProfile p = profile(name), all = profile(EVERYONE);
        List<Tactic> fresh = new ArrayList<>();
        for (Tactic t : Tactic.values()) if (p.tactic(t).n < 2) fresh.add(t);
        if (!fresh.isEmpty() && random.nextDouble() < Math.min(1, explore.get())) return fresh.get(random.nextInt(fresh.size()));

        // the average episode against anyone: the starting guess for a tactic nobody has scored yet
        double sum = 0, sq = 0;
        long n = 0;
        for (OpponentProfile.Stat st : all.tactics.values()) {
            if (st.n == 0) continue;
            sum += st.mean * st.n;
            sq += (st.m2 + st.mean * st.mean * st.n);
            n += st.n;
        }
        double baseMean = n > 0 ? sum / n : 0;
        double baseSd = n > 1 ? Math.sqrt(Math.max(0, sq / n - baseMean * baseMean)) : 4;

        Tactic best = Tactic.BEHIND;
        double bestDraw = Double.NEGATIVE_INFINITY;
        for (Tactic t : Tactic.values()) {
            OpponentProfile.Stat own = p.tactic(t), prior = all.tactic(t);
            // shrink a player's numbers toward everyone's results with this tactic (or the overall average)
            double priorMean = prior.n > 0 ? prior.mean : baseMean, k = 3;
            double mean = (own.n * own.mean + k * priorMean) / (own.n + k);
            double spread = Math.max(own.n > 1 ? own.sd() : baseSd, Math.max(baseSd, 2.0)); // never fully certain
            double sd = spread / Math.sqrt(own.n + 1) * explore.get();
            double draw = mean + random.nextGaussian() * sd;
            if (draw > bestDraw) { bestDraw = draw; best = t; }
        }
        return best;
    }

    // ---- what KillAura uses -----------------------------------------------------------------------------

    /** The tactic for this fight (starts an episode on first use). */
    public Tactic tacticFor(LivingEntity t) {
        Live l = live.computeIfAbsent(t.getId(), id -> new Live(id, nameOf(t)));
        l.lastSeen = tick;
        if (l.tactic == null) { l.tactic = choose(l.name); l.episodeStart = tick; }
        return l.tactic;
    }

    /** Where to walk against this target right now, or null to stand. */
    public Vec3 walkPoint(LivingEntity t) {
        Tactic tactic = tacticFor(t);
        AutoWalk walk = Prism.modules().get(AutoWalk.class);
        Vec3 me = mc.player.position(), tp = t.position();
        double dist = Math.hypot(me.x - tp.x, me.z - tp.z);
        return switch (tactic) {
            case CHASE, CHASE_CLOSE -> dist <= tactic.radius ? null : towards(tp, me, tactic.radius);
            case BEHIND, BEHIND_WIDE -> walk.orbitPoint(t, tactic.radius, Math.max(3.6, tactic.radius), 0, true);
            case ORBIT_CW -> walk.orbitPoint(t, tactic.radius, tactic.radius, 1, false);
            case ORBIT_CCW -> walk.orbitPoint(t, tactic.radius, tactic.radius, -1, false);
            case KITE -> kite(t, tp, me, dist);
        };
    }

    /** In right after they swing (their cooldown is our window), back out beyond their reach before the next one. */
    private Vec3 kite(LivingEntity t, Vec3 tp, Vec3 me, double dist) {
        Live l = live.get(t.getId());
        OpponentProfile p = profile(l.name);
        double gap = p.swingGap.meanOr(12, 3);
        double theirReach = p.reach.meanOr(3.0, 3);
        boolean window = tick - l.lastSwing < gap * 0.55;
        double want = window ? 2.0 : Math.max(theirReach + 0.45, 3.2);
        if (Math.abs(dist - want) < 0.25) return null;
        return towards(tp, me, want);
    }

    /** The point {@code r} from the target on our side of it. */
    private static Vec3 towards(Vec3 target, Vec3 me, double r) {
        Vec3 d = new Vec3(me.x - target.x, 0, me.z - target.z);
        if (d.lengthSqr() < 1e-4) d = new Vec3(1, 0, 0);
        d = d.normalize();
        return new Vec3(target.x + d.x * r, target.y, target.z + d.z * r);
    }

    /**
     * Aim with lead: their centre moved along their current motion by our ping, plus their usual strafe, then kept
     * inside the box the server checks our hit against so the ray still lands.
     */
    public Vec3 aimPoint(LivingEntity t) {
        AABB box = HitboxUtil.attackBox(t);
        Vec3 c = box.getCenter();
        if (!predict.get()) return c;
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        double ticks = 1 + (info == null ? 0 : info.getLatency() / 50.0);
        Vec3 v = new Vec3(t.getX() - t.xo, 0, t.getZ() - t.zo);
        Vec3 lead = c.add(v.scale(ticks));
        double inset = 0.08;
        return new Vec3(Mth.clamp(lead.x, box.minX + inset, box.maxX - inset), Mth.clamp(lead.y, box.minY + 0.2, box.maxY - 0.2), Mth.clamp(lead.z, box.minZ + inset, box.maxZ - inset));
    }

    // ---- profiles ---------------------------------------------------------------------------------------

    public OpponentProfile profile(String name) {
        if (profiles == null) load();
        return profiles.computeIfAbsent(name, OpponentProfile::new);
    }

    public List<String> known() {
        if (profiles == null) load();
        List<String> out = new ArrayList<>(profiles.keySet());
        out.remove(EVERYONE);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public boolean forget(String name) {
        if (profiles == null) load();
        String key = null;
        for (String k : profiles.keySet()) if (k.equalsIgnoreCase(name)) key = k;
        if (key == null || key.equals(EVERYONE)) return false;
        profiles.remove(key);
        save();
        return true;
    }

    /** One line about a player: record, their habits, and the best tactic so far. */
    public String summary(String name) {
        if (profiles == null) load();
        for (String k : profiles.keySet()) if (k.equalsIgnoreCase(name)) name = k;
        OpponentProfile p = profile(name);
        Tactic best = null;
        double bestMean = Double.NEGATIVE_INFINITY;
        for (var e : p.tactics.entrySet()) if (e.getValue().n > 0 && e.getValue().mean > bestMean) { bestMean = e.getValue().mean; best = e.getKey(); }
        double gap = p.swingGap.meanOr(0, 1);
        String strafe = p.strafe.n < 20 ? "?" : Math.abs(p.strafe.mean) < 0.3 ? "straight" : p.strafe.mean > 0 ? "clockwise" : "counter-clockwise";
        return String.format(Locale.ROOT, "%s: %d fights, dealt %.0f / took %.0f, reach %.2f, %s, strafes %s, turns %.0f°/t, crits %.0f%%, best %s",
            name, p.fights, p.damageDealt, p.damageTaken, p.reach.meanOr(0, 1),
            gap > 0 ? String.format(Locale.ROOT, "%.1f CPS", 20 / gap) : "? CPS", strafe, p.turnRate.meanOr(0, 1),
            100 * p.critRate.meanOr(0, 1), best == null ? "unknown yet" : best.label + String.format(Locale.ROOT, " (%+.1f/s)", bestMean));
    }

    private Path file() { return Prism.config().folder().resolve("ai").resolve("opponents.json"); }

    private void load() {
        profiles = new HashMap<>();
        try {
            if (Files.exists(file())) {
                Map<String, OpponentProfile> m = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, OpponentProfile>>() {}.getType());
                if (m != null) profiles.putAll(m);
            }
        } catch (Exception e) {
            Prism.LOG.error("[ai] could not read opponents.json", e);
        }
    }

    public void save() {
        if (profiles == null) return;
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(profiles));
            dirty = false;
        } catch (Exception e) {
            Prism.LOG.error("[ai] could not save opponents.json", e);
        }
    }

    /** Fights going on now with their tactic (HUD / dev checks). */
    public Map<String, Tactic> current() {
        Map<String, Tactic> out = new HashMap<>();
        for (Live l : live.values()) if (l.tactic != null) out.put(l.name, l.tactic);
        return out;
    }
}
