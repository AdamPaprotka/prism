package dev.prismglass.util;

import dev.prismglass.Prism;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory helpers. Inventory indices: 0-8 hotbar, 9-35 main, 36-39 armor (feet..head), 40 offhand.
 * Screen slot ids (player container): hotbar 36-44, main 9-35, armor 5-8 (head..feet), offhand 45.
 */
public final class InvUtil {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int previousSlot = -1;

    private InvUtil() {}

    public static int findHotbar(Predicate<ItemStack> filter) {
        for (int i = 0; i < 9; i++) if (filter.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    public static int findHotbar(Item item) { return findHotbar(s -> s.is(item)); }

    /** Searches main inventory first (to keep the hotbar free), then the hotbar. */
    public static int findInventory(Predicate<ItemStack> filter) {
        for (int i = 9; i < 36; i++) if (filter.test(mc.player.getInventory().getItem(i))) return i;
        for (int i = 0; i < 9; i++) if (filter.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    public static int findInventory(Item item) { return findInventory(s -> s.is(item)); }

    public static int count(Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        ItemStack off = mc.player.getOffhandItem();
        if (off.is(item)) n += off.getCount();
        return n;
    }

    public static int toScreenSlot(int invSlot) {
        if (invSlot < 9) return 36 + invSlot;
        if (invSlot < 36) return invSlot;
        if (invSlot < 40) return 8 - (invSlot - 36);
        return 45;
    }

    /** Selects a hotbar slot. Remembers the old slot for {@link #swapBack()}. */
    public static boolean swap(int slot, boolean remember) {
        if (slot < 0 || slot > 8) return false;
        int current = mc.player.getInventory().getSelectedSlot();
        if (remember && previousSlot == -1) previousSlot = current;
        if (current == slot) return true;
        mc.player.getInventory().setSelectedSlot(slot);
        mc.gameMode.ensureHasSentCarriedItem();
        return true;
    }

    public static void swapBack() {
        if (previousSlot == -1) return;
        restore(previousSlot);
        previousSlot = -1;
    }

    /**
     * Returns to a slot after an action. If an attack/place already went out this tick, switching now
     * would trip PacketOrderE, so the switch is deferred to the start of next tick.
     */
    public static void restore(int slot) {
        if (slot < 0 || slot > 8 || mc.player.getInventory().getSelectedSlot() == slot) return;
        if (Prism.guard().canSwitchSlot()) swap(slot, false);
        else Prism.guard().deferSlot(slot);
    }

    /** Silent swap: only the server sees the slot change. Always pair with {@link #silentBack}. */
    public static void silentSwap(int slot) {
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    public static void silentBack() {
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
    }

    /**
     * True when an inventory click won't trip Grim MultiActionsC (server sees us not sprinting, no WASD).
     * If false, movement is released for a tick so it becomes true; just try again next tick.
     */
    public static boolean canClick() {
        return Prism.invGuard().ready();
    }

    private static void prepareClick() {
        if (Prism.anticheat().invStrict.get() && mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }
    }

    /** Moves an inventory item into the offhand with a single SWAP click (button 40). */
    public static void toOffhand(int invSlot) {
        prepareClick();
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, toScreenSlot(invSlot), 40,
            ContainerInput.SWAP, mc.player);
    }

    /** Swaps an inventory slot with a hotbar slot. */
    public static void swapWithHotbar(int invSlot, int hotbar) {
        prepareClick();
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, toScreenSlot(invSlot), hotbar,
            ContainerInput.SWAP, mc.player);
    }

    /** Pick up and place: moves the stack at screen slot {@code from} to screen slot {@code to}. */
    public static void move(int fromScreen, int toScreen) {
        prepareClick();
        int id = mc.player.inventoryMenu.containerId;
        mc.gameMode.handleContainerInput(id, fromScreen, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(id, toScreen, 0, ContainerInput.PICKUP, mc.player);
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(id, fromScreen, 0, ContainerInput.PICKUP, mc.player);
        }
    }

    public static void quickMove(int screenSlot) {
        prepareClick();
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, screenSlot, 0,
            ContainerInput.QUICK_MOVE, mc.player);
    }
}
