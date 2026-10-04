package dev.prismglass.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {
    public BoolSetting(String name, String description, boolean def) { super(name, description, def); }

    public void toggle() { value = !value; }

    @Override public boolean parse(String input) {
        switch (input.toLowerCase()) {
            case "true", "on", "yes", "1" -> value = true;
            case "false", "off", "no", "0" -> value = false;
            case "toggle" -> toggle();
            default -> { return false; }
        }
        return true;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }
    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) value = e.getAsBoolean(); }
}
