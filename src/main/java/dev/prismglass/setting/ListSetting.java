package dev.prismglass.setting;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A list of ids. Stored as comma text (so configs, ".set" and the modules reading it stay the same); the ClickGUI
 * opens a list editor for it with icons, add-with-autocomplete, remove and, for ordered lists, reordering.
 */
public class ListSetting extends TextSetting {
    /** TEXT: any word (e.g. a namespace); the choices are only suggestions. ENTITY: entity types (zombie, creeper...). */
    public enum Kind { BLOCK, ITEM, CHOICE, TEXT, ENTITY }

    private final Kind kind;
    private final boolean ordered;
    private final int maxSize;
    private final List<String> choices;
    private final Map<String, String> choiceIcons = new HashMap<>();
    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private List<String> universe;
    private Set<String> universeSet;

    /**
     * @param ordered order matters (e.g. hotbar layout): the editor shows move arrows and allows duplicates
     * @param maxSize 0 = unlimited
     */
    public ListSetting(String name, String description, String def, Kind kind, boolean ordered, int maxSize, List<String> choices) {
        super(name, description, def);
        this.kind = kind;
        this.ordered = ordered;
        this.maxSize = maxSize;
        this.choices = choices;
    }

    /** Icons for CHOICE entries: pairs of (entry, item id). */
    public ListSetting icons(String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) choiceIcons.put(pairs[i], pairs[i + 1]);
        return this;
    }

    public Kind kind() { return kind; }
    public boolean ordered() { return ordered; }
    public int maxSize() { return maxSize; }

    public List<String> items() {
        List<String> out = new ArrayList<>();
        for (String p : get().split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public void setItems(List<String> list) { set(String.join(",", list)); }

    private String cachedRaw;
    private Set<String> cachedSet = Set.of();

    /** ENTITY lists: is this entity's type on the list? (cached per list text: ESP asks for every entity, every frame) */
    public boolean has(net.minecraft.world.entity.Entity e) {
        String raw = get();
        if (!raw.equals(cachedRaw)) {
            Set<String> set = new HashSet<>();
            for (String i : items()) set.add(normalize(i));
            cachedSet = set;
            cachedRaw = raw;
        }
        if (cachedSet.isEmpty()) return false;
        return cachedSet.contains(shortId(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())));
    }

    public static String normalize(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    /**
     * The id as this list stores it. CHOICE entries keep their declared spelling ("InRange"): normalize() lowercases,
     * so matching them against the choices has to ignore case.
     */
    private String canonical(String id) {
        String n = normalize(id);
        if (kind == Kind.CHOICE) for (String c : choices) if (normalize(c).equals(n)) return c;
        return n;
    }

    /** Adds a valid id; false if invalid, already present (unordered lists) or full. */
    public boolean add(String id) {
        String n = canonical(id);
        if (n.isEmpty() || !valid(n)) return false;
        List<String> l = items();
        if (!ordered && l.contains(n)) return false;
        if (maxSize > 0 && l.size() >= maxSize) return false;
        l.add(n);
        setItems(l);
        return true;
    }

    public void remove(int index) {
        List<String> l = items();
        if (index < 0 || index >= l.size()) return;
        l.remove(index);
        setItems(l);
    }

    public void move(int index, int dir) {
        List<String> l = items();
        int to = index + dir;
        if (index < 0 || to < 0 || index >= l.size() || to >= l.size()) return;
        Collections.swap(l, index, to);
        setItems(l);
    }

    public boolean valid(String id) {
        if (kind == Kind.TEXT) return !normalize(id).isEmpty();
        if (kind == Kind.CHOICE) return choices.contains(canonical(id));
        universe();
        return universeSet.contains(normalize(id));
    }

    /** Every id this list accepts, for autocomplete. */
    public List<String> universe() {
        if (universe == null) {
            List<String> u = new ArrayList<>();
            switch (kind) {
                case BLOCK -> { for (Identifier id : BuiltInRegistries.BLOCK.keySet()) u.add(shortId(id)); }
                case ITEM -> { for (Identifier id : BuiltInRegistries.ITEM.keySet()) u.add(shortId(id)); }
                case ENTITY -> { for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) u.add(shortId(id)); }
                case CHOICE, TEXT -> u.addAll(choices);
            }
            if (kind == Kind.BLOCK || kind == Kind.ITEM || kind == Kind.ENTITY) Collections.sort(u);
            universe = u;
            universeSet = new HashSet<>(u);
        }
        return universe;
    }

    private static String shortId(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    /** Item shown next to an entry; EMPTY when there is none (e.g. water, a free slot). */
    public ItemStack icon(String entry) {
        ItemStack cached = iconCache.get(entry);
        if (cached != null) return cached;
        try {
            ItemStack made = makeIcon(entry);
            iconCache.put(entry, made);
            return made;
        } catch (RuntimeException e) {
            return ItemStack.EMPTY; // 26.1: item components aren't bound until a world loads (title screen); retry later
        }
    }

    private ItemStack makeIcon(String e) {
        {
            if (kind == Kind.TEXT) return ItemStack.EMPTY;
            String id = kind == Kind.CHOICE ? choiceIcons.get(e) : normalize(e);
            if (id == null) return ItemStack.EMPTY;
            Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (key == null) return ItemStack.EMPTY;
            // entities show their spawn egg (none for e.g. players, arrows)
            if (kind == Kind.ENTITY) key = Identifier.fromNamespaceAndPath(key.getNamespace(), key.getPath() + "_spawn_egg");
            Item item = kind == Kind.BLOCK ? BuiltInRegistries.BLOCK.getValue(key).asItem() : BuiltInRegistries.ITEM.getValue(key);
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        }
    }
}
