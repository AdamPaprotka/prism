package dev.prismglass.ai;

/**
 * The ways Damage AI Learn can fight someone. Each is a walking plan around the target; KillAura still decides when
 * a hit is legal. The learner tries them and keeps using whatever trades best against that player.
 */
public enum Tactic {
    /** Straight at them, stop at 2.4 (classic). */
    CHASE("Chase", 2.4),
    /** Straight at them, stand close (more of your hits land, more of theirs too). */
    CHASE_CLOSE("Close", 1.6),
    /** Circle round behind them (AutoWalk Behind). */
    BEHIND("Behind", 2.4),
    /** Behind, from further out: harder to reach back. */
    BEHIND_WIDE("BehindWide", 2.85),
    /** Keep circling clockwise (seen from above) whatever they do. */
    ORBIT_CW("OrbitCW", 2.5),
    /** Keep circling counter-clockwise. */
    ORBIT_CCW("OrbitCCW", 2.5),
    /** Stay just outside their reach and step in right after they swing (their cooldown is our window). */
    KITE("Kite", 2.7);

    public final String label;
    public final double radius;

    Tactic(String label, double radius) {
        this.label = label;
        this.radius = radius;
    }
}
