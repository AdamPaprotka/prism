package dev.prismglass.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.prismglass.Prism;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;

/**
 * Cracked (offline-mode) accounts: any name, no password, the uuid an offline server would assign. Logging in swaps
 * the client's user, so the next server you join sees that name; online-mode servers refuse it, as they do with
 * any launcher's offline mode. The account you started the game with is always one click away ("Original").
 */
public final class AccountManager {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] ADJ = {"Dark", "Silent", "Glass", "Void", "Neon", "Frost", "Ash", "Rapid", "Lunar", "Prism"};
    private static final String[] NOUN = {"Wolf", "Fox", "Raven", "Knight", "Ghost", "Drift", "Blade", "Nova", "Shade", "Storm"};

    private final Path file = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("accounts.json");
    private final List<String> accounts = new ArrayList<>();
    private final User original;
    private final ProfileKeyPairManager originalKeys;

    public AccountManager() {
        original = mc.getUser();
        originalKeys = mc.getProfileKeyPairManager();
        load();
    }

    public List<String> all() { return accounts; }

    /** @return false if the name is invalid or already saved. */
    public boolean add(String name) {
        if (!FakePlayers.validName(name)) return false;
        for (String a : accounts) if (a.equalsIgnoreCase(name)) return false;
        accounts.add(name);
        save();
        return true;
    }

    public boolean remove(String name) {
        boolean removed = accounts.removeIf(a -> a.equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    /** Switch to a cracked session with this name (saved to the list if new). */
    public boolean login(String name) {
        if (!FakePlayers.validName(name)) return false;
        add(name);
        User u = new User(name, FakePlayers.offlineUuid(name), "", Optional.empty(), Optional.empty());
        apply(u, ProfileKeyPairManager.EMPTY_KEY_MANAGER);
        if (Prism.proxies() != null) Prism.proxies().onLogin(name);
        return true;
    }

    /** Back to the account the game was launched with. */
    public void loginOriginal() { apply(original, originalKeys); }

    private void apply(User u, ProfileKeyPairManager keys) {
        mc.user = u;
        mc.profileKeyPairManager = keys;
        Prism.LOG.info("Session is now {} ({})", u.getName(), u == original ? "original" : "cracked");
    }

    public String currentName() { return mc.getUser().getName(); }
    public String originalName() { return original.getName(); }
    public boolean isOriginal() { return mc.getUser() == original; }

    public static String randomName() {
        Random r = new Random();
        String n = ADJ[r.nextInt(ADJ.length)] + NOUN[r.nextInt(NOUN.length)] + (r.nextBoolean() ? "" : "_") + r.nextInt(10, 999);
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var e : o.getAsJsonArray("accounts")) {
                String n = e.getAsString();
                if (FakePlayers.validName(n) && accounts.stream().noneMatch(a -> a.equalsIgnoreCase(n))) accounts.add(n);
            }
        } catch (Exception e) {
            Prism.LOG.warn("Could not read accounts.json", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            for (String n : accounts) a.add(n);
            o.add("accounts", a);
            Files.writeString(file, GSON.toJson(o));
        } catch (Exception e) {
            Prism.LOG.warn("Could not write accounts.json", e);
        }
    }

    /** Lower-case name for comparisons. */
    public static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
