package dev.prismglass.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.prismglass.util.ColorUtil;

/** ARGB colour with optional rainbow cycling. */
public class ColorSetting extends Setting<Integer> {
    private boolean rainbow;

    public ColorSetting(String name, String description, int argb) { super(name, description, argb); }

    public boolean isRainbow() { return rainbow; }
    public void setRainbow(boolean r) { rainbow = r; }

    /** The colour to actually draw with (rainbow resolved). */
    public int color() { return color(0); }
    public int color(int offset) { return rainbow ? ColorUtil.rainbow(offset, (value >>> 24) & 0xFF) : value; }

    @Override public boolean parse(String input) {
        if (input.equalsIgnoreCase("rainbow")) { rainbow = !rainbow; return true; }
        try {
            String s = input.startsWith("#") ? input.substring(1) : input;
            long v = Long.parseLong(s, 16);
            value = (int) (s.length() <= 6 ? (0xFF000000L | v) : v);
            return true;
        } catch (NumberFormatException e) { return false; }
    }

    @Override public String display() { return rainbow ? "rainbow" : String.format("#%08X", value); }

    @Override public JsonElement save() {
        JsonObject o = new JsonObject();
        o.addProperty("argb", value);
        o.addProperty("rainbow", rainbow);
        return o;
    }

    @Override public void load(JsonElement e) {
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.has("argb")) value = o.get("argb").getAsInt();
        if (o.has("rainbow")) rainbow = o.get("rainbow").getAsBoolean();
    }
}
