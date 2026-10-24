package dev.prismglass.module.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

/**
 * 1.8-style sword blocking. With a shield in the offhand, holding use raises the shield for real (vanilla blocking,
 * the damage reduction is the shield's) while the sword is drawn in the old block pose and the shield is hidden.
 * Without a shield the pose is visual only. The pose is vanilla's own BLOCK use transform (still in
 * ItemInHandRenderer for non-shield blockers), applied after the arm placement so attacks swing through it like
 * 1.8 block-hitting.
 */
public class SwordBlock extends Module {
    public final BoolSetting hideShield = bool("HideShield", true, "Don't draw the offhand shield while blocking with a sword.");
    public final BoolSetting visualOnly = bool("WithoutShield", true, "Show the block pose while use is held even with no shield (looks only).");
    public final BoolSetting anyWeapon = bool("AnyWeapon", false, "Axes and maces too, not just swords.");

    public SwordBlock() { super("SwordBlock", "1.8-style sword blocking animation (real block with an offhand shield).", Category.COMBAT); }

    private boolean weapon(ItemStack s) {
        return s.is(ItemTags.SWORDS) || (anyWeapon.get() && (s.is(ItemTags.AXES) || s.is(net.minecraft.world.item.Items.MACE)));
    }

    /** Main hand holds a sword and we're blocking with the offhand shield (or holding use, when allowed). */
    public boolean blocking() {
        var p = mc.player;
        if (p == null || !weapon(p.getMainHandItem())) return false;
        if (p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND && p.getUseItem().getItem() instanceof ShieldItem) return true;
        return visualOnly.get() && mc.options.keyUse.isDown() && !(p.getOffhandItem().getItem() instanceof ShieldItem) && mc.gui.screen() == null;
    }

    public boolean hidesOffhand() {
        return hideShield.get() && blocking() && mc.player.getOffhandItem().getItem() instanceof ShieldItem;
    }

    /** SwordBlockMixin: right before the main-hand item is drawn. */
    public void applyPose(HumanoidArm arm, PoseStack pose) {
        float invert = arm == HumanoidArm.RIGHT ? 1f : -1f;
        pose.translate(invert * -0.14142136F, 0.08F, 0.14142136F);
        pose.rotate(Axis.XP.rotationDegrees(-102.25F));
        pose.rotate(Axis.YP.rotationDegrees(invert * 13.365F));
        pose.rotate(Axis.ZP.rotationDegrees(invert * 78.05F));
    }
}
