package dev.prismglass.module.anarchy;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.AABB;
import java.util.HashSet;
import java.util.Set;

/**
 * For servers that have a /dupe command: dupes what you hold until your inventory is full, gets rid of it all except
 * one item, and repeats for a number of cycles. Started with {@code .spamdupe [cycles]}, stopped with
 * {@code .spamdupe stop}.
 *
 * <p>One cycle: send the command (with N worked out to fill exactly your free space), wait for the items to arrive,
 * then Output: ThrowUp aims straight up (silent rotation) and throws every other stack whole, then takes the held
 * stack, puts one back and throws the rest; ToChest opens the nearest chest / barrel / shulker within reach and
 * shift-clicks the copies into it (the held stack minus one goes in too), moving on to the next one when it's full.
 * A cycle that's underway when the count is reached is finished, so you always end up holding one.
 */
public class SpamDupe extends Module {
    public final TextSetting command = text("Command", "dupe {n}", "The server's dupe command without the slash; {n} becomes the amount.");
    public final ModeSetting amount = mode("NMeans", "Stacks", "What the server's N counts: Stacks = N more copies of your held stack, Items = N more items, Multiply = your held stack x N (DupeDucks-style /dupe <1-10>; set MaxN to the plugin's max).", "Stacks", "Items", "Multiply");
    public final NumberSetting maxN = num("MaxN", 64, 1, 2304, 1, "Never ask for more than this (some plugins kick for big numbers).");
    public final ModeSetting output = mode("Output", "ThrowUp", "Where the copies go: ThrowUp = thrown straight up, ToChest = into the nearest chests.", "ThrowUp", "ToChest");
    public final NumberSetting cyclesSetting = num("Cycles", 5, 1, 1000, 1, "Cycles to run when .spamdupe gets no number (or the module is turned on).");
    public final NumberSetting cycleDelay = num("CycleDelay", 4, 1, 100, 1, "Ticks between cycles (servers often rate limit /dupe).");
    public final NumberSetting throwsPerTick = num("MovesPerTick", 9, 1, 36, 1, "Stack throws / shift-clicks sent per tick.");
    public final NumberSetting chestRange = num("ChestRange", 4.5, 2, 6, 0.5, "ToChest: use containers this close.").visibleWhen(() -> output.is("ToChest"));
    public final NumberSetting maxNearby = num("MaxNearby", 600, 50, 5000, 50, "Stop when this many item entities are around you (keeps you and the server from freezing).");

    private enum Phase { DUPE, WAIT, THROW, KEEP_ONE, OPEN, STORE, PAUSE }

    private Phase phase;
    private int wait, before, cycles, target, heldSlot, openWait;
    private ItemStack template = ItemStack.EMPTY;
    private final Set<BlockPos> fullChests = new HashSet<>();
    private BlockPos chest;

    public SpamDupe() { super("SpamDupe", "Uses the server's /dupe to fill your inventory, throws it up or puts it in chests, repeats.", Category.ANARCHY); }

    /** {@code .spamdupe [cycles]}: runs that many cycles (0 = the Cycles setting). */
    public void start(int cycleCount) {
        target = cycleCount > 0 ? cycleCount : cyclesSetting.getInt();
        if (isEnabled()) onEnable(); else setEnabled(true);
    }

    @Override
    public void onEnable() {
        if (target <= 0) target = cyclesSetting.getInt();
        ItemStack hand = mc.player.getMainHandItem();
        if (hand.isEmpty()) { ChatUtil.error("SpamDupe: hold the item to dupe"); target = 0; toggle(); return; }
        template = hand.copyWithCount(1);
        heldSlot = mc.player.getInventory().getSelectedSlot();
        phase = Phase.DUPE;
        wait = 0;
        cycles = 0;
        fullChests.clear();
        chest = null;
    }

    @Override
    public void onDisable() {
        target = 0;
        if (mc.player == null) return;
        if (chest != null && mc.gui.screen() != null) mc.player.closeContainer();
        ChatUtil.info("SpamDupe: " + cycles + " cycles");
    }

    @Override public String getInfo() { return cycles + "/" + target; }

    @Override
    public void onTick() {
        // count reached: never start another dupe, but finish the current one so you end with exactly one
        if (cycles >= target && (phase == Phase.DUPE || phase == Phase.PAUSE)) { toggle(); return; }
        boolean storing = phase == Phase.OPEN || phase == Phase.STORE;
        if (mc.gui.screen() != null && !storing) return; // the clicks below assume the player inventory menu
        if (wait > 0) { wait--; return; }
        if (mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(mc.player.blockPosition()).inflate(16)).size() > maxNearby.getInt()) {
            ChatUtil.error("SpamDupe: " + maxNearby.getInt() + "+ items around you, stopping");
            toggle();
            return;
        }
        switch (phase) {
            case DUPE -> {
                int n = amountToFill();
                if (n <= 0) { phase = afterDupe(); return; } // already full: just get rid of it
                before = count();
                mc.getConnection().sendCommand(command.get().replace("{n}", String.valueOf(Math.min(n, maxN.getInt()))).replaceFirst("^/", ""));
                phase = Phase.WAIT;
                wait = 20; // give up waiting after a second
            }
            case WAIT -> phase = afterDupe(); // reached when the wait ran out (no items came)
            case THROW -> {
                if (!aimUp()) return;
                int sent = 0;
                for (int i = 0; i < 36 && sent < throwsPerTick.getInt(); i++) {
                    if (i == heldSlot || !same(mc.player.getInventory().getItem(i))) continue;
                    click(mc.player.inventoryMenu, InvUtil.toScreenSlot(i), 1, ContainerInput.THROW); // ctrl+Q: the whole stack
                    sent++;
                }
                if (sent == 0) phase = Phase.KEEP_ONE;
            }
            case KEEP_ONE -> {
                ItemStack held = mc.player.getInventory().getItem(heldSlot);
                if (same(held) && held.getCount() > 1) {
                    if (!aimUp()) return;
                    int slot = InvUtil.toScreenSlot(heldSlot);
                    click(mc.player.inventoryMenu, slot, 0, ContainerInput.PICKUP);   // take the stack
                    click(mc.player.inventoryMenu, slot, 1, ContainerInput.PICKUP);   // right click: put one back
                    click(mc.player.inventoryMenu, -999, 0, ContainerInput.PICKUP);   // click outside: throw the rest
                }
                endCycle();
            }
            case OPEN -> open();
            case STORE -> store();
            case PAUSE -> phase = Phase.DUPE;
        }
    }

    @Override
    public void onPostTick() {
        // the dupe landed: get rid of it right away instead of waiting out the timeout
        if (phase == Phase.WAIT && count() > before) { phase = afterDupe(); wait = 0; }
    }

    private Phase afterDupe() { return output.is("ToChest") ? Phase.OPEN : Phase.THROW; }

    private void endCycle() {
        cycles++;
        phase = Phase.PAUSE;
        wait = cycleDelay.getInt();
    }

    private boolean aimUp() {
        Prism.rotations().request(mc.player.getYRot(), -90f, 80);
        return Prism.rotations().getServerPitch() <= -80f;
    }

    /** ToChest: open the nearest container that isn't known to be full. */
    private void open() {
        if (storeMenu() != null) { phase = Phase.STORE; return; }
        if (chest == null || fullChests.contains(chest)) {
            chest = nearestChest();
            openWait = 0;
            if (chest == null) { ChatUtil.error("SpamDupe: no chest with room within " + chestRange.get() + " blocks"); toggle(); return; }
        }
        if (openWait > 0) { if (--openWait == 0) chest = null; return; } // clicked: wait for the screen (retry after 1s)
        Direction face = BlockUtil.breakFace(chest);
        if (face == null) face = Direction.UP;
        if (BlockUtil.click(chest, face, mc.player.getInventory().getSelectedSlot(), false, 80) == BlockUtil.Result.PLACED) openWait = 20;
    }

    /** ToChest: shift-click the copies in, then the held stack minus one. */
    private void store() {
        AbstractContainerMenu menu = storeMenu();
        if (menu == null) { phase = Phase.OPEN; return; }
        int sent = 0;
        for (Slot s : menu.slots) {
            if (sent >= throwsPerTick.getInt()) break;
            if (!(s.container instanceof Inventory) || s.getContainerSlot() >= 36 || s.getContainerSlot() == heldSlot || !same(s.getItem())) continue;
            click(menu, s.index, 0, ContainerInput.QUICK_MOVE);
            sent++;
        }
        if (sent > 0) {
            // anything left after shift-clicking means this one is full
            if (!chestHasRoom(menu)) { fullChests.add(chest); mc.player.closeContainer(); phase = Phase.OPEN; }
            return;
        }
        Slot held = null;
        for (Slot s : menu.slots) if (s.container instanceof Inventory && s.getContainerSlot() == heldSlot) held = s;
        if (held != null && same(held.getItem()) && held.getItem().getCount() > 1) {
            int free = freeChestSlot(menu);
            if (free == -1) { fullChests.add(chest); mc.player.closeContainer(); phase = Phase.OPEN; return; }
            click(menu, held.index, 0, ContainerInput.PICKUP);   // take the stack
            click(menu, held.index, 1, ContainerInput.PICKUP);   // put one back
            // the rest into the chest, over as many slots as it takes
            while (!menu.getCarried().isEmpty() && (free = freeChestSlot(menu)) != -1) click(menu, free, 0, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) {
                // this one filled up: put the rest back and finish in the next chest
                click(menu, held.index, 0, ContainerInput.PICKUP);
                fullChests.add(chest);
                mc.player.closeContainer();
                phase = Phase.OPEN;
                return;
            }
        }
        if (!chestHasRoom(menu)) fullChests.add(chest);
        mc.player.closeContainer();
        endCycle();
    }

    private AbstractContainerMenu storeMenu() {
        AbstractContainerMenu m = mc.player.containerMenu;
        return mc.gui.screen() != null && (m instanceof ChestMenu || m instanceof ShulkerBoxMenu) ? m : null;
    }

    private boolean chestHasRoom(AbstractContainerMenu menu) { return freeChestSlot(menu) != -1; }

    private int freeChestSlot(AbstractContainerMenu menu) {
        for (Slot s : menu.slots) {
            if (s.container instanceof Inventory) continue;
            ItemStack it = s.getItem();
            if (it.isEmpty() || (same(it) && it.getCount() < it.getMaxStackSize())) return s.index;
        }
        return -1;
    }

    private BlockPos nearestChest() {
        int r = (int) Math.ceil(chestRange.get());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(mc.player.blockPosition().offset(-r, -r, -r), mc.player.blockPosition().offset(r, r + 1, r))) {
            BlockEntity be = mc.level.getBlockEntity(p);
            boolean box = be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || (be instanceof ShulkerBoxBlockEntity && !(template.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock));
            if (!box || fullChests.contains(p) || !BlockUtil.inPlaceRange(p)) continue;
            double d = mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p));
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    private void click(AbstractContainerMenu menu, int screenSlot, int button, ContainerInput input) {
        mc.gameMode.handleContainerInput(menu.containerId, screenSlot, button, input, mc.player);
    }

    private boolean same(ItemStack s) { return !s.isEmpty() && ItemStack.isSameItemSameComponents(s, template); }

    private int count() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (same(mc.player.getInventory().getItem(i))) n += mc.player.getInventory().getItem(i).getCount();
        return n;
    }

    /** The N that fills every free spot for this item in the main inventory + hotbar. */
    private int amountToFill() {
        int max = template.getMaxStackSize(), free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty()) free += max;
            else if (same(s)) free += max - s.getCount();
        }
        if (amount.is("Items")) return free;
        int held = mc.player.getInventory().getItem(heldSlot).getCount();
        if (held <= 0) return 0;
        if (amount.is("Multiply")) { int x = 1 + free / held; return x >= 2 ? x : 0; } // x N: you end up with held * N
        return free / held;
    }

    public int cycles() { return cycles; }

    /** Dev checks. */
    public String debug() { return phase + " chest=" + chest + " full=" + fullChests.size() + " openWait=" + openWait + " wait=" + wait + " screen=" + mc.gui.screen() + " count=" + count() + " carried=" + mc.player.containerMenu.getCarried() + " menu=" + mc.player.containerMenu.getClass().getSimpleName() + " room=" + (storeMenu() != null && chestHasRoom(storeMenu())); }
}
