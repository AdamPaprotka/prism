package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Removes the tweening between ticks, so entities jump from tick to tick instead of gliding (position, rotation,
 * walk cycle, everything). Blocks mode also snaps them to the block grid, so they hop a whole block at a time.
 * Visual only: nothing is sent to the server.
 */
public class SnapAnims extends Module {
    public final ModeSetting mode = mode("Mode", "Blocks", "Ticks: no smoothing between ticks. Blocks: also snapped to the block grid.", "Ticks", "Blocks");
    public final NumberSetting grid = num("Grid", 1, 0.25, 4, 0.25, "Blocks: grid size in blocks.");
    public final BoolSetting snapY = bool("SnapY", true, "Blocks: snap height too (jumps hop a whole cell).");
    public final NumberSetting yawStep = num("YawStep", 0, 0, 90, 15, "Snap body and head turning to steps of this many degrees (0 = off).");
    public final BoolSetting players = bool("Players", true, "Other players.");
    public final BoolSetting self = bool("Self", false, "You, in third person.");
    public final BoolSetting mobs = bool("Mobs", false, "Hostile and passive mobs.");
    public final BoolSetting others = bool("Others", false, "Items, projectiles, boats, minecarts...");

    public SnapAnims() { super("SnapAnims", "Choppy snap movement: no tweening, optionally blockified.", Category.RENDER); }

    /** The module, if it should change this entity's render state. */
    public static SnapAnims active(Entity e) {
        SnapAnims m = Prism.modules().get(SnapAnims.class);
        if (m == null || !m.isEnabled()) return null;
        boolean on;
        if (e instanceof LocalPlayer) on = m.self.get();
        else if (e instanceof Player) on = m.players.get();
        else if (e instanceof Enemy || e instanceof net.minecraft.world.entity.Mob) on = m.mobs.get();
        else on = m.others.get();
        return on ? m : null;
    }

    /** Called on the freshly extracted state (extracted with partial tick 1, i.e. the latest tick, no tweening). */
    public void snap(EntityRenderState s) {
        if (mode.is("Blocks")) {
            double g = grid.get();
            s.x = Math.floor(s.x / g) * g + g / 2;
            s.z = Math.floor(s.z / g) * g + g / 2;
            if (snapY.get()) s.y = Math.floor(s.y / g + 1e-4) * g;
        }
        double step = yawStep.get();
        if (step > 0 && s instanceof LivingEntityRenderState l) {
            float head = l.bodyRot + l.yRot;
            l.bodyRot = round(l.bodyRot, step);
            l.yRot = Mth.wrapDegrees(round(head, step) - l.bodyRot);
        }
    }

    private static float round(float deg, double step) { return (float) (Math.round(deg / step) * step); }

    @Override
    public String getInfo() { return mode.get(); }
}
