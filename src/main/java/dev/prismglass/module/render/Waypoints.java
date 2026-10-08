package dev.prismglass.module.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ColorSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.ChatUtil;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Named waypoints per server/world ({@code .wp add|del|list|clear}): a beam and a label with the distance, shown in
 * their own dimension and converted between the overworld and the nether (x/8, z/8). Optional death waypoint.
 */
public class Waypoints extends Module {
    public final ColorSetting color = color("Color", 0xFF8AB4FF, "Beam and label colour.");
    public final BoolSetting deathPoint = bool("DeathWaypoint", true, "Add a \"Death\" waypoint where you die.");
    public final BoolSetting otherDim = bool("OtherDimension", true, "Show overworld waypoints in the nether (and back) at the converted spot.");
    public final NumberSetting maxDistance = num("MaxDistance", 100000, 100, 100000, 100, "Hide waypoints farther than this.");

    /** One waypoint; dim is "overworld", "the_nether" or "the_end". */
    public record Point(String name, int x, int y, int z, String dim) {}

    private record Label(String text, double sx, double sy, int color) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, List<Point>> all;
    private final List<Label> labels = new ArrayList<>();
    private boolean wasDead;

    public Waypoints() { super("Waypoints", "Named waypoints with beams and distance labels (.wp).", Category.RENDER); }

    // ---- storage: prism/waypoints.json, one list per server address / singleplayer world ---------------------

    private Path file() { return Prism.config().folder().resolve("waypoints.json"); }

    private Map<String, List<Point>> data() {
        if (all != null) return all;
        try {
            if (Files.exists(file())) all = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, List<Point>>>() {}.getType());
        } catch (Exception e) {
            Prism.LOG.error("[waypoints] load failed", e);
        }
        if (all == null) all = new HashMap<>();
        return all;
    }

    private void save() {
        try { Files.writeString(file(), GSON.toJson(data())); } catch (Exception e) { Prism.LOG.error("[waypoints] save failed", e); }
    }

    private String key() {
        if (mc.getSingleplayerServer() != null) return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return mc.getCurrentServer() != null ? "mp:" + mc.getCurrentServer().ip : "unknown";
    }

    private static String dimOf(Level level) { return level.dimension().identifier().getPath(); }

    public List<Point> points() { return data().computeIfAbsent(key(), k -> new ArrayList<>()); }

    public void add(String name, int x, int y, int z) {
        points().removeIf(p -> p.name().equalsIgnoreCase(name));
        points().add(new Point(name, x, y, z, dimOf(mc.level)));
        save();
    }

    public boolean remove(String name) {
        boolean r = points().removeIf(p -> p.name().equalsIgnoreCase(name));
        if (r) save();
        return r;
    }

    public void clear() { points().clear(); save(); }

    // ---- death waypoint ---------------------------------------------------------------------------

    @Override
    public void onTick() {
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && deathPoint.get()) {
            add("Death", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
            ChatUtil.info(String.format("Death waypoint at %d %d %d", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()));
        }
        wasDead = dead;
    }

    // ---- drawing -------------------------------------------------------------------------------------

    /** Where a waypoint shows in the dimension we're in, or null if it doesn't. */
    private Vec3 shownAt(Point p, String here) {
        if (p.dim().equals(here)) return new Vec3(p.x() + 0.5, p.y(), p.z() + 0.5);
        if (!otherDim.get()) return null;
        if (p.dim().equals("overworld") && here.equals("the_nether")) return new Vec3(p.x() / 8.0 + 0.5, p.y(), p.z() / 8.0 + 0.5);
        if (p.dim().equals("the_nether") && here.equals("overworld")) return new Vec3(p.x() * 8.0 + 0.5, p.y(), p.z() * 8.0 + 0.5);
        return null;
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        labels.clear();
        String here = dimOf(mc.level);
        Vec3 eye = mc.player.getEyePosition(delta);
        for (Point p : points()) {
            Vec3 at = shownAt(p, here);
            if (at == null) continue;
            double dist = eye.distanceTo(at);
            if (dist > maxDistance.get()) continue;
            boolean converted = !p.dim().equals(here);
            int c = converted ? ColorUtil.fade(color.color(), 0.6f) : color.color();
            // a beam; far waypoints are drawn closer along the same line so they stay visible
            Vec3 anchor = dist > 200 ? eye.add(at.subtract(eye).normalize().scale(200)) : at;
            Render3D.line(anchor, anchor.add(0, dist > 200 ? 20 : 120, 0), ColorUtil.withAlpha(c, 150));
            double[] s = Render3D.project(anchor.add(0, 2, 0));
            if (s != null) {
                String d = dist >= 1000 ? String.format("%.1fk", dist / 1000) : String.format("%.0f", dist);
                labels.add(new Label(p.name() + (converted ? " §7(" + (p.dim().equals("overworld") ? "OW" : "Nether") + ")" : "") + " §7" + d + "m", s[0], s[1], c));
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (labels.isEmpty()) return;
        Glass.Style style = Prism.modules().get(ClickGui.class).panelStyle();
        for (Label l : labels) {
            float w = mc.font.width(l.text()) + 14, x = (float) l.sx() - w / 2, y = (float) l.sy() - 13;
            Glass.panel(ctx, x, y, w, 13, style);
            ctx.fill((int) x + 4, (int) y + 4, (int) x + 7, (int) y + 9, l.color());
            ctx.text(mc.font, l.text(), (int) x + 10, (int) y + 3, 0xFFFFFFFF, true);
        }
    }

    @Override public String getInfo() { return mc.level == null ? null : String.valueOf(points().size()); }
}
