package dev.prismglass.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.prismglass.util.KeyUtil;

/** GLFW key code, or a mouse button encoded by {@link KeyUtil}. -1 = unbound. */
public class KeySetting extends Setting<Integer> {
    public KeySetting(String name, String description, int def) { super(name, description, def); }

    @Override public boolean parse(String input) {
        int k = KeyUtil.fromName(input);
        if (k == Integer.MIN_VALUE) return false;
        value = k;
        return true;
    }

    @Override public String display() { return KeyUtil.name(value); }

    @Override public JsonElement save() { return new JsonPrimitive(value); }
    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) value = e.getAsInt(); }
}
