package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.client.ClickGui;
import java.util.ArrayList;
import java.util.List;

/** Glass nametags: name, health, ping, distance and gear, always readable through walls. */
public class Nametags extends Module {
    public final BoolSetting health = bool("Health", true, "Show health.");
    public final BoolSetting ping = bool("Ping", true, "Show ping.");
    public final BoolSetting distance = bool("Distance", false, "Show distance.");
    public final BoolSetting gear = bool("Gear", true, "Show armour and held items.");
    public final NumberSetting scale = num("Scale", 1.0, 0.5, 2, 0.05, "Tag size.");

    private record Tag(Player player, double x, double y, double dist) {}
    private final List<Tag> tags = new ArrayList<>();

    public Nametags() { super("Nametags", "Better player nametags.", Category.RENDER); }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        tags.clear();
        for (Player p : mc.level.players()) {
            if (p == mc.player && mc.options.getCameraType().isFirstPerson()) continue;
            Vec3 top = p.getPosition(delta).add(0, p.getBbHeight() + 0.35, 0);
            double[] s = Render3D.project(top);
            if (s != null) tags.add(new Tag(p, s[0], s[1], mc.player.distanceTo(p)));
        }
        tags.sort((a, b) -> Double.compare(b.dist(), a.dist()));
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        Glass.Style style = Prism.modules().get(ClickGui.class).panelStyle();
        for (Tag t : tags) {
            Player p = t.player();
            StringBuilder sb = new StringBuilder();
            boolean friend = Prism.friends().isFriend(p);
            sb.append(friend ? "\u00A7b" : "\u00A7f").append(p.getName().getString());
            if (health.get()) {
                float hp = DamageUtil.health(p);
                String col = hp > 15 ? "\u00A7a" : hp > 8 ? "\u00A7e" : "\u00A7c";
                sb.append(' ').append(col).append(String.format("%.1f", hp));
            }
            if (ping.get() && mc.getConnection() != null) {
                PlayerInfo e = mc.getConnection().getPlayerInfo(p.getUUID());
                if (e != null) sb.append(" \u00A77").append(e.getLatency()).append("ms");
            }
            if (distance.get()) sb.append(" \u00A77").append(String.format("%.0fm", t.dist()));
            String text = sb.toString();

            float sc = scale.getFloat();
            ctx.pose().pushMatrix();
            ctx.pose().translate((float) (t.x()), (float) (t.y()));
            ctx.pose().scale(sc, sc);
            float w = mc.font.width(text) + 10;
            Glass.panel(ctx, -w / 2, -12, w, 13, style);
            ctx.text(mc.font, text, (int) (-w / 2 + 5), -9, 0xFFFFFFFF, true);
            if (gear.get()) {
                List<ItemStack> items = new ArrayList<>();
                items.add(p.getMainHandItem());
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    items.add(p.getItemBySlot(slot));
                }
                items.add(p.getOffhandItem());
                items.removeIf(ItemStack::isEmpty);
                int x = -items.size() * 9;
                for (ItemStack s : items) {
                    ctx.item(s, x, -30);
                    ctx.itemDecorations(mc.font, s, x, -30);
                    x += 18;
                }
            }
            ctx.pose().popMatrix();
        }
    }
}
