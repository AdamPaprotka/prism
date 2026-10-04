package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.client.ClickGui;
import java.util.*;

/** Remembers where players logged out (box + glass label). */
public class LogoutSpots extends Module {
    public final ColorSetting color = color("Color", 0xFFFF5C7A, "Box colour.");

    private record Spot(String name, AABB box, float health, long time) {}
    private final Map<UUID, Spot> spots = new LinkedHashMap<>();
    private final Map<UUID, Player> lastSeen = new HashMap<>();
    private final List<double[]> labels = new ArrayList<>();
    private final List<Spot> labelSpots = new ArrayList<>();

    public LogoutSpots() { super("LogoutSpots", "Shows where players logged out.", Category.RENDER); }

    @Override public void onWorldJoin() { spots.clear(); lastSeen.clear(); }

    @Override
    public void onTick() {
        lastSeen.clear();
        for (Player p : mc.level.players()) {
            if (p != mc.player) lastSeen.put(p.getUUID(), p);
            spots.remove(p.getUUID()); // came back
        }
    }

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (!(event.packet instanceof ClientboundPlayerInfoRemovePacket remove)) return;
        mc.execute(() -> {
            for (UUID id : remove.profileIds()) {
                Player p = lastSeen.get(id);
                if (p != null) spots.put(id, new Spot(p.getName().getString(), p.getBoundingBox(), DamageUtil.health(p), System.currentTimeMillis()));
            }
        });
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        labels.clear();
        labelSpots.clear();
        for (Spot s : spots.values()) {
            Render3D.box(s.box(), ColorUtil.withAlpha(color.color(), 40), color.color(), true);
            double[] p = Render3D.project(new Vec3(s.box().getCenter().x, s.box().maxY + 0.4, s.box().getCenter().z));
            if (p != null) { labels.add(p); labelSpots.add(s); }
        }
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        Glass.Style style = Prism.modules().get(ClickGui.class).panelStyle();
        for (int i = 0; i < labels.size(); i++) {
            Spot s = labelSpots.get(i);
            long ago = (System.currentTimeMillis() - s.time()) / 1000;
            String text = s.name() + " \u00A77logged " + ago + "s ago \u00A7c" + String.format("%.1f", s.health());
            float w = mc.font.width(text) + 10;
            float x = (float) labels.get(i)[0] - w / 2, y = (float) labels.get(i)[1] - 12;
            Glass.panel(ctx, x, y, w, 13, style);
            ctx.text(mc.font, text, (int) x + 5, (int) y + 3, 0xFFFFFFFF, true);
        }
    }

    @Override public String getInfo() { return String.valueOf(spots.size()); }
}
