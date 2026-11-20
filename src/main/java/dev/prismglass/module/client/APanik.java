package dev.prismglass.module.client;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Auto panic: when another player shows up, every module goes off (except the Keep list, StreamProof by default),
 * and with Restore on, the same modules come back once nobody's been around for ClearDelay seconds.
 *
 * <p>Triggers (any of the chosen ones):
 * <ul>
 *   <li>Rendered - a player is loaded at all (inside entity tracking range).</li>
 *   <li>InRange - a player within Range blocks.</li>
 *   <li>OnScreen - a player inside your field of view (Range applies).</li>
 *   <li>CanSeeYou - a player with a clear line of sight to any part of you (Range applies).</li>
 *   <li>LookingAtYou - a player whose crosshair is within LookAngle of you, through walls or not (Range applies).</li>
 * </ul>
 * Only the modules APanik itself switched off come back on, and only if they're still off.
 */
public class APanik extends Module {
    public final ListSetting triggers = choices("Triggers", "Rendered,InRange", "What counts as a player showing up.", false, 0,
        "Rendered", "InRange", "OnScreen", "CanSeeYou", "LookingAtYou");
    public final NumberSetting range = num("Range", 32, 1, 256, 1, "Distance for InRange, OnScreen, CanSeeYou and LookingAtYou.");
    public final NumberSetting lookAngle = num("LookAngle", 10, 1, 45, 1, "How closely their crosshair has to be on you for LookingAtYou, in degrees.");
    public final BoolSetting lookThroughWalls = bool("LookThroughWalls", false, "LookingAtYou also counts with blocks in between.");
    public final BoolSetting restore = bool("Restore", true, "Switch the modules back on when nobody's around any more.");
    public final NumberSetting clearDelay = num("ClearDelay", 3, 0, 60, 0.5, "Seconds without a trigger before Restore switches them back on.").visibleWhen(restore::get);
    public final TextSetting keep = text("Keep", "StreamProof", "Modules APanik never switches off, comma separated.");
    public final BoolSetting ignoreFriends = bool("IgnoreFriends", true, "Friends don't trigger it.");
    public final BoolSetting ignoreNpcs = bool("IgnoreNPCs", true, "Players missing from the tab list (NPCs) don't trigger it.");
    public final BoolSetting alert = bool("Alert", true, "Chat message (a hidden toast while StreamProof is on) saying who triggered it.");

    /** Modules this panic switched off, to bring back on Restore. */
    private final List<Module> panicked = new ArrayList<>();
    private boolean active;
    private int clearTicks;

    public APanik() {
        super("APanik", "Auto panic: every module off (except StreamProof) when a player shows up, back on when they're gone.", Category.CLIENT);
    }

    @Override public String getInfo() { return active ? "PANIK" : null; }

    /** Switched off by the current panic (the config saves these as on, so a panic never sticks). */
    public boolean holding(Module m) { return active && panicked.contains(m); }

    @Override public void onDisable() {
        // switching APanik off mid-panic hands everything back (with Restore on)
        if (active && restore.get()) bringBack();
        active = false;
        panicked.clear();
    }

    @Override public void onTick() {
        String who = trigger();
        if (who != null) {
            clearTicks = 0;
            if (!active) panic(who);
        } else if (active && restore.get() && ++clearTicks >= Math.round(clearDelay.get() * 20)) {
            bringBack();
            active = false;
            if (alert.get()) ChatUtil.info("APanik: clear, modules back on");
        }
    }

    /** The name of the first player that sets a trigger off, or null. */
    private String trigger() {
        ClientPacketListener conn = mc.getConnection();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (ignoreFriends.get() && Prism.friends().isFriend(p)) continue;
            if (ignoreNpcs.get() && conn != null && conn.getPlayerInfo(p.getUUID()) == null) continue;
            if (on("Rendered")) return p.getGameProfile().name();
            double d = p.distanceTo(mc.player);
            if (d > range.get()) continue;
            if (on("InRange") || on("OnScreen") && onScreen(p) || on("CanSeeYou") && canSee(p) || on("LookingAtYou") && looksAt(p, d))
                return p.getGameProfile().name();
        }
        return null;
    }

    /** (case-insensitive: ".set APanik Triggers inrange" works too) */
    private boolean on(String t) {
        for (String i : triggers.items()) if (i.equalsIgnoreCase(t)) return true;
        return false;
    }

    /** Inside the camera's field of view (horizontal FOV scaled to the window's aspect, plus a little margin). */
    private boolean onScreen(Player p) {
        Vec3 cam = mc.player.getEyePosition();
        Vec3 to = p.getBoundingBox().getCenter().subtract(cam).normalize();
        Vec3 look = mc.player.getViewVector(1f);
        double vfov = Math.toRadians(mc.options.fov().get());
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double hfov = 2 * Math.atan(Math.tan(vfov / 2) * aspect);
        // the diagonal half-angle covers the whole screen rectangle
        double half = Math.atan(Math.hypot(Math.tan(vfov / 2), Math.tan(hfov / 2))) + Math.toRadians(5);
        return Math.acos(Math.max(-1, Math.min(1, look.dot(to)))) <= half;
    }

    /** A clear line from their eyes to your eyes, chest or feet. */
    private boolean canSee(Player p) {
        Vec3 eye = p.getEyePosition();
        AABB me = mc.player.getBoundingBox();
        Vec3[] points = { mc.player.getEyePosition(), me.getCenter(), new Vec3(me.getCenter().x, me.minY + 0.1, me.getCenter().z) };
        for (Vec3 pt : points) if (clear(eye, pt, p)) return true;
        return false;
    }

    private boolean clear(Vec3 from, Vec3 to, Player p) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    /** Their look ray hits your hitbox grown by the LookAngle cone at this distance (StaffWatcher's check). */
    private boolean looksAt(Player p, double d) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(p.getXRot(), p.getYHeadRot());
        AABB box = mc.player.getBoundingBox().inflate(Math.tan(Math.toRadians(lookAngle.get())) * d);
        if (box.clip(eye, eye.add(look.scale(range.get() + 8))).isEmpty() && !box.contains(eye)) return false;
        return lookThroughWalls.get() || clear(eye, mc.player.getEyePosition(), p);
    }

    private void panic(String who) {
        active = true;
        panicked.clear();
        List<String> keepNames = new ArrayList<>();
        for (String k : keep.get().split(",")) if (!k.isBlank()) keepNames.add(k.trim().toLowerCase(Locale.ROOT));
        for (Module m : Prism.modules().all()) {
            if (!m.isEnabled() || m == this || m.internal() || m instanceof AntiCheat || m instanceof Hud || m instanceof dev.prismglass.module.Service) continue;
            if (keepNames.contains(m.getName().toLowerCase(Locale.ROOT))) continue;
            m.setEnabled(false);
            panicked.add(m);
        }
        if (alert.get()) ChatUtil.error("APanik: " + who + " showed up, " + panicked.size() + " modules off");
    }

    private void bringBack() {
        for (Module m : panicked) if (!m.isEnabled()) m.setEnabled(true);
        panicked.clear();
    }
}
