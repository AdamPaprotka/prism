package dev.prismglass.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.prismglass.util.KeyUtil;

/**
 * SDL scancode, or a mouse button encoded by {@link KeyUtil}. -1 = unbound.
 * Saved as the vanilla key name ("key.keyboard.r", "key.mouse.middle"); a bare number is a 1.6.3-or-older
 * GLFW code and gets translated once on load.
 */
public class KeySetting extends Setting<Integer> {
    public KeySetting(String name, String description, int def) { super(name, description, def); }

    @Override public boolean parse(String input) {
        int k = KeyUtil.fromName(input);
        if (k == Integer.MIN_VALUE) return false;
        value = k;
        return true;
    }

    @Override public String display() { return KeyUtil.name(value); }

    @Override public JsonElement save() { return new JsonPrimitive(KeyUtil.saveName(value)); }
    @Override public void load(JsonElement e) {
        if (!e.isJsonPrimitive()) return;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) value = KeyUtil.fromLegacy(p.getAsInt());
        else {
            int k = KeyUtil.fromSaveName(p.getAsString());
            if (k != Integer.MIN_VALUE) value = k;
        }
    }
}
