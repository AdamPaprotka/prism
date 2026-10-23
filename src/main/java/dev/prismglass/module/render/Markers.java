package dev.prismglass.module.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.Render3D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Outlines you place to plan builds and fights: circles (a horizontal ring), spheres (rings of latitude and
 * longitude) and cuboids (two corners). Managed with .marker, saved per server/world in prism/markers.json like
 * waypoints, and only drawn in the dimension they were made in.
 */
public class Markers extends Module {
    public final ColorSetting color = color("Color", 0xE04DABF7, "Line colour.");
    public final NumberSetting detail = num("Detail", 48, 16, 128, 8, "Segments per ring.");
    public final BoolSetting throughWalls = bool("ThroughWalls", false, "Draw boxes through blocks (lines always do).");

    /** type: circle / sphere / cuboid; circle and sphere use (x, y, z, r), cuboid uses both corners. */
    public record Marker(String name, String type, double x, double y, double z, double r, double x2, double y2, double z2, String dim) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, List<Marker>> all;

    public Markers() { super("Markers", "Circles, spheres and boxes you place with .marker.", Category.RENDER); }

    private Path file() { return Prism.config().folder().resolve("markers.json"); }

    private Map<String, List<Marker>> data() {
        if (all != null) return all;
        try {
            if (Files.exists(file())) all = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, List<Marker>>>() {}.getType());
        } catch (Exception e) {
            Prism.LOG.error("[markers] load failed", e);
        }
        if (all == null) all = new HashMap<>();
        return all;
    }

    private void save() {
        try { Files.writeString(file(), GSON.toJson(data())); } catch (Exception e) { Prism.LOG.error("[markers] save failed", e); }
    }

    private String key() {
        if (mc.getSingleplayerServer() != null) return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return mc.getCurrentServer() != null ? "mp:" + mc.getCurrentServer().ip : "unknown";
    }

    private String dim() { return mc.level.dimension().identifier().getPath(); }

    public List<Marker> markers() { return data().computeIfAbsent(key(), k -> new ArrayList<>()); }

    public void addSphere(String name, boolean sphere, double x, double y, double z, double r) {
        markers().removeIf(m -> m.name().equalsIgnoreCase(name));
        markers().add(new Marker(name, sphere ? "sphere" : "circle", x, y, z, r, 0, 0, 0, dim()));
        save();
    }

    public void addCuboid(String name, double x1, double y1, double z1, double x2, double y2, double z2) {
        markers().removeIf(m -> m.name().equalsIgnoreCase(name));
        markers().add(new Marker(name, "cuboid", Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), 0, Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1, dim()));
        save();
    }

    public boolean remove(String name) {
        boolean r = markers().removeIf(m -> m.name().equalsIgnoreCase(name));
        if (r) save();
        return r;
    }

    public void clear() { markers().clear(); save(); }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        String dim = dim();
        int col = color.color(), seg = detail.getInt();
        for (Marker m : markers()) {
            if (!m.dim().equals(dim)) continue;
            switch (m.type()) {
                case "cuboid" -> Render3D.box(new AABB(m.x(), m.y(), m.z(), m.x2(), m.y2(), m.z2()), 0, col, throughWalls.get());
                case "circle" -> ring(m.x(), m.y(), m.z(), m.r(), seg, col);
                case "sphere" -> {
                    for (int lat = -60; lat <= 60; lat += 30) {
                        double rr = m.r() * Math.cos(Math.toRadians(lat));
                        ring(m.x(), m.y() + m.r() * Math.sin(Math.toRadians(lat)), m.z(), rr, seg, col);
                    }
                    for (int lon = 0; lon < 180; lon += 30) meridian(m, Math.toRadians(lon), seg, col);
                }
                default -> {}
            }
        }
    }

    private static void ring(double cx, double y, double cz, double r, int seg, int col) {
        Vec3 prev = null;
        for (int i = 0; i <= seg; i++) {
            double a = i * Math.PI * 2 / seg;
            Vec3 p = new Vec3(cx + Math.cos(a) * r, y, cz + Math.sin(a) * r);
            if (prev != null) Render3D.line(prev, p, col);
            prev = p;
        }
    }

    /** A full great circle through the poles at this longitude. */
    private static void meridian(Marker m, double lon, int seg, int col) {
        Vec3 prev = null;
        for (int i = 0; i <= seg; i++) {
            double a = i * Math.PI * 2 / seg;
            double h = Math.cos(a) * m.r();
            Vec3 p = new Vec3(m.x() + Math.cos(lon) * h, m.y() + Math.sin(a) * m.r(), m.z() + Math.sin(lon) * h);
            if (prev != null) Render3D.line(prev, p, col);
            prev = p;
        }
    }

    @Override public String getInfo() { return mc.level == null || markers().isEmpty() ? null : String.valueOf(markers().size()); }
}
