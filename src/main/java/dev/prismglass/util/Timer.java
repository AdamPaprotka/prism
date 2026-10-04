package dev.prismglass.util;

public class Timer {
    private long last = System.currentTimeMillis();

    public boolean passed(double ms) { return System.currentTimeMillis() - last >= ms; }
    public long elapsed() { return System.currentTimeMillis() - last; }
    public void reset() { last = System.currentTimeMillis(); }

    /** Returns true and resets if the delay passed. */
    public boolean tick(double ms) {
        if (!passed(ms)) return false;
        reset();
        return true;
    }
}
