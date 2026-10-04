package dev.prismglass.module;

public enum Category {
    COMBAT("Combat", 0xFFFF6B81),
    ANARCHY("Anarchy", 0xFFFFA94D),
    PLAYER("Player", 0xFF69DB7C),
    MOVEMENT("Movement", 0xFF4DABF7),
    RENDER("Render", 0xFFB197FC),
    WORLD("World", 0xFF38D9A9),
    CLIENT("Client", 0xFFE9ECEF);

    public final String title;
    /** Tint used for the panel's glass highlight. */
    public final int tint;

    Category(String title, int tint) {
        this.title = title;
        this.tint = tint;
    }
}
