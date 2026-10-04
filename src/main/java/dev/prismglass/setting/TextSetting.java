package dev.prismglass.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class TextSetting extends Setting<String> {
    public TextSetting(String name, String description, String def) { super(name, description, def); }

    @Override public boolean parse(String input) { value = input; return true; }
    @Override public JsonElement save() { return new JsonPrimitive(value); }
    @Override public void load(JsonElement e) { if (e.isJsonPrimitive()) value = e.getAsString(); }
}
