package dev.prismglass.scan;

public enum Confidence {
    LOW("low", "§8"), MEDIUM("medium", "§e"), HIGH("high", "§f"), CONFIRMED("confirmed", "§a");

    public final String label;
    /** Chat colour code. */
    public final String color;

    Confidence(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public static Confidence of(int score) {
        return score >= 97 ? CONFIRMED : score >= 80 ? HIGH : score >= 50 ? MEDIUM : LOW;
    }
}
