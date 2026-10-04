package dev.prismglass.manager;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public final class FriendManager {
    private final Set<String> friends = Collections.synchronizedSet(new LinkedHashSet<>());

    public boolean isFriend(String name) { return friends.contains(name.toLowerCase()); }

    public boolean isFriend(Entity e) {
        return e instanceof Player p && isFriend(p.getGameProfile().name());
    }

    public boolean add(String name) { return friends.add(name.toLowerCase()); }
    public boolean remove(String name) { return friends.remove(name.toLowerCase()); }
    public Set<String> all() { return friends; }
    public void clear() { friends.clear(); }
}
