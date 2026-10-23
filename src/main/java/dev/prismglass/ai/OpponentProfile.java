package dev.prismglass.ai;

import java.util.EnumMap;
import java.util.Map;

/**
 * What Damage AI Learn knows about one opponent (or "*" = everyone, the prior for people it hasn't met). Plain
 * fields so Gson writes it to prism/ai/opponents.json as is. Means are running (Welford) so they never need the
 * raw samples.
 */
public final class OpponentProfile {
    public String name;
    public int fights;
    public int hitsTaken, hitsLanded;
    public double damageTaken, damageDealt;

    /** Distance (centre to centre) at which they hit us. */
    public Stat reach = new Stat();
    /** Ticks between their swings while fighting us. */
    public Stat swingGap = new Stat();
    /** Sideways speed around us, + = clockwise seen from above (their strafe habit). */
    public Stat strafe = new Stat();
    /** Speed toward us (+) or away (-). */
    public Stat approach = new Stat();
    /** How fast their head turns toward us, degrees per tick. */
    public Stat turnRate = new Stat();
    /** Fraction of their hits that were crits (they were falling). */
    public Stat critRate = new Stat();

    /** Reward per tactic: damage dealt minus damage taken per second of using it. */
    public Map<Tactic, Stat> tactics = new EnumMap<>(Tactic.class);

    public OpponentProfile() {}

    public OpponentProfile(String name) { this.name = name; }

    public Stat tactic(Tactic t) { return tactics.computeIfAbsent(t, k -> new Stat()); }

    /** Running mean / variance (Welford). */
    public static final class Stat {
        public long n;
        public double mean, m2;

        public void add(double x) {
            n++;
            double d = x - mean;
            mean += d / n;
            m2 += d * (x - mean);
        }

        public double variance() { return n > 1 ? m2 / (n - 1) : 0; }

        public double sd() { return Math.sqrt(variance()); }

        /** Mean, or {@code fallback} with too few samples to trust. */
        public double meanOr(double fallback, int minSamples) { return n >= minSamples ? mean : fallback; }
    }
}
