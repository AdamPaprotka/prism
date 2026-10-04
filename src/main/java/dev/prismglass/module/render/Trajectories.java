package dev.prismglass.module.render;

import net.minecraft.core.component.predicates.DataComponentPredicate.Type;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Predicts where your bow / pearl / snowball / trident / xp bottle will land. */
public class Trajectories extends Module {
    public final ColorSetting color = color("Color", 0xFF8AB4FF, "Path colour.");

    public Trajectories() { super("Trajectories", "Shows projectile paths.", Category.RENDER); }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        ItemStack stack = mc.player.getMainHandItem();
        Item item = stack.getItem();
        double velocity, gravity, drag = 0.99;
        float pitchOffset = 0;
        if (item instanceof BowItem) {
            if (!mc.player.isUsingItem()) return;
            float pull = BowItem.getPowerForTime(mc.player.getTicksUsingItem());
            if (pull < 0.1f) return;
            velocity = pull * 3.0; gravity = 0.05;
        } else if (item instanceof CrossbowItem) {
            if (!CrossbowItem.isCharged(stack)) return;
            velocity = 3.15; gravity = 0.05;
        } else if (item instanceof TridentItem) {
            if (!mc.player.isUsingItem()) return;
            velocity = 2.5; gravity = 0.05;
        } else if (item instanceof EnderpearlItem || item instanceof SnowballItem || item instanceof EggItem) {
            velocity = 1.5; gravity = 0.03;
        } else if (item instanceof ExperienceBottleItem) {
            velocity = 0.7; gravity = 0.07; pitchOffset = -20f;
        } else return;

        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot() + pitchOffset;
        Vec3 pos = mc.player.getPosition(delta).add(0, mc.player.getEyeHeight() - 0.1, 0);
        Vec3 vel = RotationUtil.direction(yaw, pitch).scale(velocity).add(mc.player.getDeltaMovement().x, mc.player.onGround() ? 0 : mc.player.getDeltaMovement().y, mc.player.getDeltaMovement().z);

        int c = color.color();
        for (int i = 0; i < 300; i++) {
            // vanilla order: gravity, then drag, then move
            vel = vel.add(0, -gravity, 0).scale(drag);
            Vec3 next = pos.add(vel);
            BlockHitResult bhr = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            EntityHitResult ehr = ProjectileUtil.getEntityHitResult(mc.player, pos, next, new AABB(pos, next).inflate(1), e -> e != mc.player && e.isPickable(), pos.distanceToSqr(next));
            if (ehr != null) {
                Render3D.line(pos, ehr.getLocation(), c);
                Render3D.box(ehr.getEntity().getBoundingBox(), ColorUtil.withAlpha(0xFFFF5C7A, 60), 0xFFFF5C7A, true);
                return;
            }
            if (bhr.getType() == HitResult.Type.BLOCK) {
                Render3D.line(pos, bhr.getLocation(), c);
                Vec3 h = bhr.getLocation();
                Render3D.box(new AABB(h.x - 0.15, h.y - 0.15, h.z - 0.15, h.x + 0.15, h.y + 0.15, h.z + 0.15), ColorUtil.withAlpha(c, 80), c, true);
                return;
            }
            Render3D.line(pos, next, ColorUtil.fade(c, 1f - i / 300f));
            pos = next;
            if (pos.y < mc.level.getMinY() - 10) return;
        }
    }
}
