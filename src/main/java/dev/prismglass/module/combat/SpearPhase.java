package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Lunge dashes on a key. A spear with Lunge throws you forward (0.458 blocks/tick per level, horizontally) every
 * time you stab, even into thin air, as long as you aren't gliding, swimming or riding and have more than 6 food.
 * DashKey swaps to your best Lunge spear, stabs the way you look (or the way you walk with Direction = Move) and
 * swaps back the next tick; Chain keeps dashing while the key is held, as fast as the attack cooldown allows.
 * It's the server that pushes you, so it is legit movement everywhere. Costs spear durability and hunger.
 */
public class SpearPhase extends Module {
    public final KeySetting dashKey = key("DashKey", KeyUtil.NONE, "Press to dash (None = dash once when the module turns on).");
    public final ModeSetting direction = mode("Direction", "Look", "Where to dash.", "Look", "Move");
    public final BoolSetting chain = bool("Chain", true, "Keep dashing while the key is held.");
    public final BoolSetting flat = bool("Flat", true, "Stab level so the push is all horizontal.");

    private boolean wasDown;
    private int restore = -1, stage;

    public SpearPhase() { super("SpearPhase", "Lunge spear dashes on a key (swaps the spear in and out).", Category.COMBAT); }

    @Override
    public void onEnable() { stage = dashKey.get() == KeyUtil.NONE ? 1 : 0; }

    @Override
    public void onTick() {
        boolean down = KeyUtil.isDown(dashKey.get()) && mc.screen == null;
        if (down && (!wasDown || chain.get())) stage = 1;
        // swap back once the stab went out (the swap reset the charge, so it can take a few ticks)
        if (restore != -1 && stage == 0) { InvUtil.restore(restore); restore = -1; }
        boolean go = stage == 1;
        wasDown = down;
        if (!go || !canLunge()) return;
        int slot = InvUtil.findHotbar(this::lungeSpear);
        if (slot == -1) { if (stage == 1) { ChatUtil.error("SpearPhase: no Lunge spear in your hotbar"); stage = 0; } return; }
        int prev = mc.player.getInventory().getSelectedSlot();
        if (slot != prev) {
            if (!Prism.guard().canSwitchSlot()) return;
            InvUtil.swap(slot, false);
            restore = prev;
            return; // the swap resets the attack charge: stab next tick
        }
        if (mc.player.getAttackStrengthScale(0f) < 1f) return;
        float yaw = mc.player.getYRot();
        if (direction.is("Move")) {
            var mv = mc.player.input.getMoveVector();
            if (mv.lengthSquared() > 0) yaw -= (float) Math.toDegrees(Math.atan2(mv.x, mv.y));
        }
        float pitch = flat.get() ? 0 : mc.player.getXRot();
        // the stab and its push go along the rotation the server has: turn there first, stab once it's sent
        Prism.rotations().request(yaw, pitch, 85);
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(Prism.rotations().getServerYaw() - yaw)) > 2 || Math.abs(Prism.rotations().getServerPitch() - pitch) > 2) return;
        PiercingWeapon pw = mc.player.getMainHandItem().get(DataComponents.PIERCING_WEAPON);
        mc.gameMode.piercingAttack(pw);
        mc.player.swing(InteractionHand.MAIN_HAND);
        mc.player.resetAttackStrengthTicker();
        stage = 0;
        dashes++;
    }

    public int dashes;

    /** The conditions vanilla's Lunge effect checks. */
    private boolean canLunge() {
        var p = mc.player;
        return !p.isFallFlying() && !p.isInWater() && !p.isPassenger() && (p.isCreative() || p.getFoodData().getFoodLevel() > 6);
    }

    private boolean lungeSpear(ItemStack s) {
        if (!s.has(DataComponents.PIERCING_WEAPON)) return false;
        var lunge = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.LUNGE);
        return lunge.isPresent() && EnchantmentHelper.getItemEnchantmentLevel(lunge.get(), s) > 0;
    }
}
