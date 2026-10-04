package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.*;
import dev.prismglass.util.ColorUtil;
import dev.prismglass.util.Render3D;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shows where entities really are on the server.
 *
 * <p>Your client draws other players interpolated toward the newest position the server sent (3 ticks of
 * smoothing), and that newest position is already ~half your ping old. Against fast targets (elytra, speed)
 * the drawn player can be blocks behind. This draws:
 * <ul>
 *   <li><b>Server</b>: the newest position the server sent ({@code LivingEntity.serverX/Y/Z}).</li>
 *   <li><b>Predicted</b>: server position + measured velocity x (your ping / 50ms) ticks, i.e. roughly where
 *       the target is when your attack packet arrives at the server. Aim here for moving/flying targets.</li>
 *   <li>A path from the drawn body to server to predicted, so the gap is obvious.</li>
 *   <li>Optionally your own last-sent server position (useful with Blink / under lag).</li>
 * </ul>
 * Render only, sends nothing. Note: Grim validates hits against the drawn-to-server box, not the predicted
 * one, so on Grim the predicted box is for timing your approach; hit the server box.
 */
public class AccurateMotion extends Module {
    public final NumberSetting maxPlayers = num("MaxPlayers", 5, 1, 30, 1, "Only show the nearest N entities.");
    public final BoolSetting server = bool("ServerBox", true, "Box at the newest server position.");
    public final BoolSetting predicted = bool("Predicted", true, "Box where they'll be when your hit arrives.");
    public final ModeSetting lead = mode("Lead", "Ping", "How far ahead to predict.", "Ping", "Custom");
    public final NumberSetting leadTicks = num("LeadTicks", 2, 0, 20, 0.5, "Custom mode: ticks to predict ahead.");
    public final NumberSetting minGap = num("MinGap", 0.15, 0, 2, 0.05, "Only draw when the server position differs this much from the drawn one.");
    public final BoolSetting path = bool("Path", true, "Line from drawn body to server to predicted.");
    public final BoolSetting labels = bool("Labels", true, "Glass label with the gap and speed.");
    public final BoolSetting mobs = bool("Mobs", false, "Also for mobs.");
    public final BoolSetting self = bool("Self", false, "Your own last-sent server position.");
    public final ColorSetting serverColor = color("ServerColor", 0xFF4DD2FF, "Server box colour.");
    public final ColorSetting predictColor = color("PredictColor", 0xFFFF5CF0, "Predicted box colour.");

    /** Per-entity server-position history for velocity. */
    private static final class Track {
        Vec3 last;
        long lastChangeTick;
        Vec3 velocity = Vec3.ZERO; // blocks per tick
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private long tick;
    private volatile Vec3 selfServer;

    private record Label(double x, double y, String text) {}
    private final List<Label> pending = new ArrayList<>();

    public AccurateMotion() { super("AccurateMotion", "Shows players' real server positions (aim help vs elytra/fast targets).", Category.RENDER); }

    @Override public void onEnable() { tracks.clear(); selfServer = null; }
    @Override public void onWorldJoin() { tracks.clear(); selfServer = null; }

    @Override
    public void onTick() {
        tick++;
        Set<Integer> seen = new HashSet<>();
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player) continue;
            if (!(e instanceof Player) && !mobs.get()) continue;
            seen.add(e.getId());
            Vec3 pos = le.getInterpolation().position();
            Track t = tracks.computeIfAbsent(e.getId(), k -> new Track());
            if (t.last == null) { t.last = pos; t.lastChangeTick = tick; continue; }
            if (!pos.equals(t.last)) {
                long dt = Math.max(1, tick - t.lastChangeTick);
                Vec3 v = pos.subtract(t.last).scale(1.0 / dt);
                // light smoothing so one jittery packet doesn't throw the prediction
                t.velocity = t.velocity.scale(0.35).add(v.scale(0.65));
                t.last = pos;
                t.lastChangeTick = tick;
            } else if (tick - t.lastChangeTick > 3) {
                t.velocity = Vec3.ZERO; // no updates for a while = standing still
            }
        }
        tracks.keySet().retainAll(seen);
    }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (e.packet instanceof ServerboundMovePlayerPacket p && p.hasPosition() && mc.player != null) {
            selfServer = new Vec3(p.getX(mc.player.getX()), p.getY(mc.player.getY()), p.getZ(mc.player.getZ()));
        }
    }

    private double leadTicks() {
        if (lead.is("Custom")) return leadTicks.get();
        PlayerInfo entry = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        int ping = entry == null ? 0 : entry.getLatency();
        // the server position we have is ~ping/2 old and our hit needs ~ping/2 to arrive: total ~ping
        return Math.min(20, ping / 50.0);
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        pending.clear();
        double ahead = leadTicks();
        List<LivingEntity> nearest = new ArrayList<>();
        for (var e : mc.level.entitiesForRendering()) {
            if (e instanceof LivingEntity le && e != mc.player && e.isAlive() && tracks.containsKey(e.getId())) nearest.add(le);
        }
        nearest.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
        if (nearest.size() > maxPlayers.getInt()) nearest = nearest.subList(0, maxPlayers.getInt());
        for (LivingEntity e : nearest) {
            Track t = tracks.get(e.getId());
            if (t == null || t.last == null) continue;

            Vec3 drawnPos = e.getPosition(delta);
            AABB drawn = Render3D.lerpBox(e, delta);
            Vec3 serverPos = t.last;
            Vec3 predictPos = serverPos.add(t.velocity.scale(ahead));
            double gap = drawnPos.distanceTo(serverPos);
            double predictGap = drawnPos.distanceTo(predictPos);
            if (Math.max(gap, predictGap) < minGap.get()) continue;

            AABB serverBox = drawn.move(serverPos.subtract(drawnPos));
            AABB predictBox = drawn.move(predictPos.subtract(drawnPos));
            int sc = serverColor.color(), pc = predictColor.color();
            if (server.get()) Render3D.box(serverBox, ColorUtil.withAlpha(sc, 30), sc, true);
            if (predicted.get() && ahead > 0 && predictPos.distanceTo(serverPos) > 0.05) {
                Render3D.box(predictBox, ColorUtil.withAlpha(pc, 30), pc, true);
            }
            if (path.get()) {
                double h = e.getBbHeight() * 0.5;
                Render3D.line(drawnPos.add(0, h, 0), serverPos.add(0, h, 0), ColorUtil.withAlpha(sc, 200));
                if (predicted.get()) Render3D.line(serverPos.add(0, h, 0), predictPos.add(0, h, 0), ColorUtil.withAlpha(pc, 200));
            }
            if (labels.get()) {
                double[] s = Render3D.project((predicted.get() ? predictPos : serverPos).add(0, e.getBbHeight() + 0.3, 0));
                if (s != null) {
                    double speed = t.velocity.horizontalDistance() * 20;
                    pending.add(new Label(s[0], s[1], String.format("%.1fm off §7| %.1f b/s", predictGap, speed)));
                }
            }
        }
        if (self.get() && selfServer != null && selfServer.distanceTo(mc.player.position()) > 0.05) {
            AABB me = mc.player.getBoundingBox().move(selfServer.subtract(mc.player.position()));
            Render3D.box(me, 0x30FFFFFF, 0xFFFFFFFF, true);
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        if (pending.isEmpty()) return;
        Glass.Style style = Prism.modules().get(ClickGui.class).panelStyle();
        for (Label l : pending) {
            float w = mc.font.width(l.text()) + 10;
            float x = (float) l.x() - w / 2, y = (float) l.y() - 12;
            Glass.panel(ctx, x, y, w, 13, style);
            ctx.text(mc.font, l.text(), (int) x + 5, (int) y + 3, 0xFFFFFFFF, true);
        }
    }

    @Override public String getInfo() { return lead.is("Ping") ? String.format("%.1ft", leadTicks()) : lead.get(); }
}
