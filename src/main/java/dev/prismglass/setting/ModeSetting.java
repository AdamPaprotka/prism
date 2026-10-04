package dev.prismglass.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

public class ModeSetting extends Setting<String> {
    private final List<String> modes;

    public ModeSetting(String name, String description, String def, String... modes) {
        super(name, description, def);
        this.modes = List.of(modes);
    }

    public List<String> getModes() { return modes; }
    public boolean is(String mode) { return value.equalsIgnoreCase(mode); }

    public void cycle(boolean forward) {
        int i = modes.indexOf(value);
        int n = modes.size();
        value = modes.get(((forward ? i + 1 : i - 1) % n + n) % n);
    }

    @Override public boolean parse(String input) {
        for (String m : modes) if (m.equalsIgnoreCase(input)) { value = m; return true; }
        return false;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }
    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) parse(e.getAsString()); }
}
