package dev.prismglass.module.player;

import dev.prismglass.manager.MovementHooks;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.util.InvUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;
import dev.prismglass.util.ChatUtil;

/**
 * Elytra never loses durability.
 *
 * <p>Vanilla (LivingEntity#tickGliding, server side) damages the elytra when (glidingTicks + 1) hits 20, 40,
 * 60... and glidingTicks resets to 0 whenever you stop gliding. So before tick 20 we briefly wear a chestplate,
 * wait until the server confirms gliding stopped (our gliding flag clears), put the elytra back and restart
 * gliding with a real jump tap - the counter starts over and never reaches 20.
 *
 * <p>Grim's elytra checks are respected: the restart is vanilla's own START_FALL_FLYING from a jump press that
 * was released the tick before (ElytraB), never two restarts in a row (ElytraC), only with the elytra worn
 * (ElytraD) and only in the air (ElytraF); waiting for the server's un-glide avoids ElytraA.
 */
public class NoElyBreak extends Module {
    public final NumberSetting swapAt = num("SwapAt", 12, 4, 17, 1, "Swap after this many gliding ticks (server damages at 20).");
    public final NumberSetting minHeight = num("MinHeight", 4, 2, 20, 1, "Skip when the ground is this close (no time to restart).");
    public final BoolSetting unbreakingRelax = bool("UnbreakingRelax", false,
        "With Unbreaking L, let L damage rolls through between swaps (fewer swaps, but each roll still costs durability 1/(L+1) of the time).");

    private enum State { IDLE, WAIT_UNGLIDE, REGLIDE }

    private State state = State.IDLE;
    private int slot = -1, waited;

    public NoElyBreak() { super("NoElyBreak", "Your elytra never loses durability.", Category.PLAYER); }

    @Override public void onEnable() { state = State.IDLE; }

    @Override
    public void onTick() {
        var p = mc.player;
        switch (state) {
            case IDLE -> {
                ItemStack elytra = p.getItemBySlot(EquipmentSlot.CHEST);
                if (!p.isFallFlying() || p.getFallFlyingTicks() < threshold(elytra) || mc.screen != null) return;
                if (!LivingEntity.canGlideUsing(elytra, EquipmentSlot.CHEST)) return;
                if (nearGround()) return;
                slot = InvUtil.findInventory(NoElyBreak::isChestplate);
                if (slot == -1 || !InvUtil.canClick()) return; // stand still a tick first (Grim MultiActionsC)
                swapChest(slot);           // chestplate on, elytra into its slot
                waited = 0;
                state = State.WAIT_UNGLIDE;
            }
            case WAIT_UNGLIDE -> {
                // the server clears our gliding flag once it ticks us without an elytra: that reset the counter
                if (p.isFallFlying() && ++waited < 10) return;
                if (!InvUtil.canClick() && waited++ < 20) return;
                swapChest(slot);           // elytra back on
                if (!p.onGround() && !p.isInWater()) MovementHooks.requestGlide();
                state = State.REGLIDE;
            }
            case REGLIDE -> {
                if (!MovementHooks.glidePending()) state = State.IDLE;
            }
        }
    }

    /**
     * Gliding ticks before we swap. Rolls happen when (glidingTicks + 1) is 20, 40, 60...; swapAt stays below
     * the first one. UnbreakingRelax lets one roll through per Unbreaking level.
     */
    private int threshold(ItemStack elytra) {
        int extraRolls = unbreakingRelax.get() ? unbreakingLevel(elytra) : 0;
        return swapAt.getInt() + 20 * extraRolls;
    }

    private static int unbreakingLevel(ItemStack stack) {
        var ench = stack.get(DataComponents.ENCHANTMENTS);
        if (ench == null) return 0;
        for (var e : ench.keySet()) {
            if (e.is(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING)) return ench.getLevel(e);
        }
        return 0;
    }

    private boolean nearGround() {
        AABB below = mc.player.getBoundingBox().expandTowards(0, -minHeight.get(), 0);
        return !mc.level.noCollision(mc.player, below);
    }

    private static boolean isChestplate(ItemStack s) {
        Equippable eq = s.get(DataComponents.EQUIPPABLE);
        return eq != null && eq.slot() == EquipmentSlot.CHEST && !LivingEntity.canGlideUsing(s, EquipmentSlot.CHEST);
    }

    /** Swap the chest armour slot (screen slot 6) with an inventory slot. Hotbar = one SWAP click. */
    private void swapChest(int invSlot) {
        int sync = mc.player.inventoryMenu.containerId;
        if (invSlot < 9) {
            mc.gameMode.handleContainerInput(sync, 6, invSlot, ContainerInput.SWAP, mc.player);
        } else {
            int screen = InvUtil.toScreenSlot(invSlot);
            mc.gameMode.handleContainerInput(sync, screen, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(sync, 6, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(sync, screen, 0, ContainerInput.PICKUP, mc.player);
            if (!mc.player.containerMenu.getCarried().isEmpty()) {
                ChatUtil.error("NoElyBreak: inventory swap failed, check your chestplate slot");
                toggle();
            }
        }
    }

    @Override public String getInfo() { return state == State.IDLE ? null : state.name(); }
}
