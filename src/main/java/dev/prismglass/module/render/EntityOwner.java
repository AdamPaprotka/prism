package dev.prismglass.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.Render3D;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Shows who owns tamed animals (wolves, cats, parrots, horses...) and projectiles in flight (arrows, tridents,
 * pearls). Names come from the tab list or a loaded player only, never a web lookup; an owner nobody can see shows as
 * the start of their UUID.
 */
public class EntityOwner extends Module {
    public final NumberSetting range = num("Range", 32, 4, 64, 1, "Label entities this close.");
    public final BoolSetting pets = bool("Pets", true, "Tamed animals.");
    public final BoolSetting projectiles = bool("Projectiles", true, "Arrows, tridents, pearls...");
    public final BoolSetting hideOwn = bool("HideOwn", false, "Skip things you own.");

    private record Label(double[] at, String text) {}

    private final List<Label> labels = new ArrayList<>();

    public EntityOwner() { super("EntityOwner", "Shows the owner of tamed animals and projectiles.", Category.RENDER); }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        labels.clear();
        double r2 = range.get() * range.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.distanceToSqr(mc.player) > r2) continue;
            UUID owner = null;
            if (pets.get() && e instanceof OwnableEntity own && own.getOwnerReference() != null) owner = own.getOwnerReference().getUUID();
            else if (projectiles.get() && e instanceof Projectile proj && proj.getOwner() != null) owner = proj.getOwner().getUUID();
            if (owner == null || (hideOwn.get() && owner.equals(mc.player.getUUID()))) continue;
            Vec3 top = e.getPosition(delta).add(0, e.getBbHeight() + 0.5, 0);
            double[] sp = Render3D.project(top);
            if (sp != null) labels.add(new Label(sp, nameOf(owner)));
        }
    }

    private String nameOf(UUID id) {
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        if (info != null) return info.getProfile().name();
        var p = mc.level.getPlayerByUUID(id);
        if (p != null) return p.getGameProfile().name();
        return id.toString().substring(0, 8);
    }

    @Override
    public void onRender2D(GuiGraphicsExtractor ctx, float delta) {
        for (Label l : labels) {
            int w = mc.font.width(l.text());
            int x = (int) l.at()[0] - w / 2, y = (int) l.at()[1] - 4;
            ctx.fill(x - 2, y - 1, x + w + 2, y + 9, 0x90000000);
            ctx.text(mc.font, l.text(), x, y, 0xFFFFD86B, false);
        }
    }

    @Override public String getInfo() { return labels.isEmpty() ? null : String.valueOf(labels.size()); }
}
