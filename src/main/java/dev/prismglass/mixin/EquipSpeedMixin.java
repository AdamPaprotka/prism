package dev.prismglass.mixin;

import net.minecraft.client.player.FirstPersonHandsAndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** FatigueAnim Equip: the hand lowers / rises slower when switching items (max 0.4 height per tick in vanilla). */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class EquipSpeedMixin {
    @ModifyConstant(method = "tick", constant = {@Constant(floatValue = 0.4F), @Constant(floatValue = -0.4F)})
    private float prism$equipSpeed(float step) {
        return step * dev.prismglass.module.render.FatigueAnim.equipScale();
    }
}
