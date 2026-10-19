package dev.prismglass.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Mth;

public class NumberSetting extends Setting<Double> {
    private final double min, max, step;

    public NumberSetting(String name, String description, double def, double min, double max, double step) {
        super(name, description, def);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public double getMin() { return min; }
    public double getMax() { return max; }
    public double getStep() { return step; }
    public int getInt() { return (int) Math.round(value); }
    public float getFloat() { return value.floatValue(); }

    @Override public void set(Double v) {
        double snapped = Math.round(v / step) * step;
        value = Mth.clamp(snapped, min, max);
    }

    /** A typed value: clamped to min..max but not snapped to the slider step, so exact values are possible. */
    public void setExact(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        value = Mth.clamp(v, min, max);
    }

    @Override public boolean parse(String input) {
        try { set(Double.parseDouble(input)); return true; } catch (NumberFormatException e) { return false; }
    }

    @Override public String display() {
        boolean onGrid = Math.abs(value / step - Math.rint(value / step)) < 1e-6;
        if (step >= 1 && onGrid) return String.valueOf((long) Math.round(value));
        int decimals = Math.max(1, (int) Math.ceil(-Math.log10(Math.min(step, 1))));
        if (!onGrid) decimals = 4; // a typed exact value: show it, trailing zeros trimmed below
        // Locale.ROOT: always "6.5", never the system locale's "6,5"
        String s = String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
        if (!onGrid && s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }
    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) set(e.getAsDouble()); }
}
