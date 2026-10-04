package dev.prismglass.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

/** A single tweakable value on a module. Everything the user sees in the GUI is a Setting. */
public abstract class Setting<T> {
    private final String name;
    private final String description;
    private final T defaultValue;
    protected T value;
    private BooleanSupplier visibility = () -> true;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public T get() { return value; }
    public void set(T value) { this.value = value; }
    public void reset() { set(defaultValue); }
    public T getDefault() { return defaultValue; }

    /** The default serialised like save() would (config codes store only what differs from this). */
    public JsonElement saveDefault() {
        T current = value;
        value = defaultValue;
        try { return save(); } finally { value = current; }
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier supplier) {
        this.visibility = supplier;
        return (S) this;
    }

    public boolean isVisible() { return visibility.getAsBoolean(); }

    /** Parse a value typed by the user (".set" command). Returns false if invalid. */
    public abstract boolean parse(String input);

    public abstract JsonElement save();

    public abstract void load(JsonElement element);

    /** Text shown after the name in compact UIs and command feedback. */
    public String display() { return String.valueOf(value); }
}
