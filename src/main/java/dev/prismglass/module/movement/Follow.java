package dev.prismglass.module.movement;

import dev.prismglass.manager.WalkGoal;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Walks after a player with real key presses (WalkGoal), so it's normal input to Grim. The target is set with
 * .follow <name>, or picked on enable from Pick (the player you look at, else the closest). When they leave render
 * distance it walks to the last place it saw them and waits there.
 */
public class Follow extends Module implements WalkGoal {
    public final ModeSetting pick = mode("Pick", "Looking", "Who to follow when no name was set with .follow.", "Looking", "Closest");
    public final NumberSetting distance = num("Distance", 2.5, 1, 10, 0.5, "Stop this close to them.");
    public final BoolSetting sprint = bool("Sprint", true, "Sprint while far away.");
    public final NumberSetting sprintFrom = num("SprintFrom", 5, 2, 20, 0.5, "Sprint only when farther than this.").visibleWhen(() -> sprint.get());
    public final BoolSetting avoidHazards = bool("AvoidHazards", true, "Don't follow them off cliffs or into lava.");

    private String targetName;
    private Vec3 lastSeen;

    public Follow() { super("Follow", "Walks after a player (.follow <name>).", Category.MOVEMENT); }

    /** .follow <name>: picks the target and turns the module on. */
    public void follow(String name) {
        targetName = name;
        lastSeen = null;
        if (!isEnabled()) toggle();
        ChatUtil.good("Following " + name);
    }

    @Override
    public void onEnable() {
        lastSeen = null;
        if (targetName != null && find() != null) return;
        Player p = null;
        if (pick.is("Looking") && mc.hitResult instanceof EntityHitResult e && e.getEntity() instanceof Player hit) p = hit;
        if (p == null) {
            double best = Double.MAX_VALUE;
            for (Player o : mc.level.players()) {
                if (o == mc.player) continue;
                double d = o.distanceToSqr(mc.player);
                if (d < best) { best = d; p = o; }
            }
        }
        if (p == null) { ChatUtil.error("Follow: nobody around (.follow <name>)"); toggle(); return; }
        targetName = p.getGameProfile().name();
        ChatUtil.info("Following " + targetName);
    }

    private Player find() {
        for (Player p : mc.level.players()) if (p != mc.player && p.getGameProfile().name().equalsIgnoreCase(targetName)) return p;
        return null;
    }

    @Override
    public void onTick() {
        Player t = targetName == null ? null : find();
        if (t != null) lastSeen = t.position();
    }

    @Override
    public Vec3 walkPoint() {
        if (lastSeen == null) return null;
        Entity t = find();
        Vec3 me = mc.player.position();
        double d = Math.hypot(lastSeen.x - me.x, lastSeen.z - me.z);
        if (t != null && d <= distance.get()) return null;
        return lastSeen;
    }

    @Override public boolean walkSprint() {
        return sprint.get() && lastSeen != null && Math.hypot(lastSeen.x - mc.player.getX(), lastSeen.z - mc.player.getZ()) > sprintFrom.get();
    }
    @Override public double walkStop() { return find() == null ? 0.4 : distance.get(); }
    @Override public boolean walkAvoidHazards() { return avoidHazards.get(); }

    public String target() { return targetName; }

    @Override public String getInfo() { return targetName == null ? null : targetName + (find() == null ? " (lost)" : ""); }
}
