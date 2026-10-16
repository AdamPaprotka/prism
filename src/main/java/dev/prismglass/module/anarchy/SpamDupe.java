package dev.prismglass.module.anarchy;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * For servers that have a /dupe command: dupes what you hold until your inventory is full, throws it all straight
 * up except one item, and repeats. Started with {@code .spamdupe [seconds]}, stopped with {@code .spamdupe stop}.
 *
 * <p>One cycle: send the command (with N worked out to fill exactly your free space), wait for the items to arrive,
 * aim straight up (silent rotation), throw every other stack of the item whole, then take the held stack, put one
 * back and throw the rest. Thrown items can't be picked up for 40 ticks (2 s in vanilla; PickupDelay setting), so
 * by default it runs for that long and then stops, before they fall back into your inventory. A cycle that's
 * underway when the time runs out is finished, so you always end up holding one.
 */
public class SpamDupe extends Module {
    public final TextSetting command = text("Command", "dupe {n}", "The server's dupe command without the slash; {n} becomes the amount.");
    public final ModeSetting amount = mode("NMeans", "Stacks", "What the server's N counts: Stacks = N more copies of your held stack, Items = N more items, Multiply = your held stack x N (DupeDucks-style /dupe <1-10>; set MaxN to the plugin's max).", "Stacks", "Items", "Multiply");
    public final NumberSetting maxN = num("MaxN", 64, 1, 2304, 1, "Never ask for more than this (some plugins kick for big numbers).");
    public final NumberSetting pickupDelay = num("PickupDelay", 2, 0.5, 30, 0.5, "Seconds thrown items can't be picked up (vanilla 2). Runs this long by default.");
    public final NumberSetting cycleDelay = num("CycleDelay", 4, 1, 100, 1, "Ticks between cycles (servers often rate limit /dupe).");
    public final NumberSetting throwsPerTick = num("ThrowsPerTick", 9, 1, 36, 1, "Stack throws sent per tick.");
    public final BoolSetting up = bool("ThrowUp", true, "Aim straight up while throwing (only the server sees it).");
    public final NumberSetting maxNearby = num("MaxNearby", 600, 50, 5000, 50, "Stop when this many item entities are around you (keeps you and the server from freezing).");

    private enum Phase { DUPE, WAIT, THROW, KEEP_ONE, PAUSE }

    private Phase phase;
    private int wait, before, cycles, heldSlot;
    private long endAt;
    private ItemStack template = ItemStack.EMPTY;

    public SpamDupe() { super("SpamDupe", "Uses the server's /dupe to fill your inventory, throws it up, repeats.", Category.ANARCHY); }

    /** {@code .spamdupe [seconds]}: runs for that long (default: PickupDelay). */
    public void start(double seconds) {
        endAt = System.currentTimeMillis() + (long) ((seconds > 0 ? seconds : pickupDelay.get()) * 1000);
        if (isEnabled()) onEnable(); else setEnabled(true);
    }

    @Override
    public void onEnable() {
        if (endAt < System.currentTimeMillis()) endAt = System.currentTimeMillis() + (long) (pickupDelay.get() * 1000);
        ItemStack hand = mc.player.getMainHandItem();
        if (hand.isEmpty()) { ChatUtil.error("SpamDupe: hold the item to dupe"); endAt = 0; toggle(); return; }
        template = hand.copyWithCount(1);
        heldSlot = mc.player.getInventory().getSelectedSlot();
        phase = Phase.DUPE;
        wait = 0;
        cycles = 0;
    }

    @Override public void onDisable() { endAt = 0; if (mc.player != null) ChatUtil.info("SpamDupe: " + cycles + " cycles"); }

    @Override public String getInfo() { return String.valueOf(cycles); }

    @Override
    public void onTick() {
        // time's up: never start another dupe, but finish throwing the current one so you end with exactly one
        boolean timeUp = System.currentTimeMillis() > endAt;
        if (timeUp && (phase == Phase.DUPE || phase == Phase.PAUSE)) { toggle(); return; }
        if (mc.screen != null) return; // inventory clicks below assume the player inventory menu
        if (wait > 0) { wait--; return; }
        if (mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(mc.player.blockPosition()).inflate(16)).size() > maxNearby.getInt()) {
            ChatUtil.error("SpamDupe: " + maxNearby.getInt() + "+ items around you, stopping");
            toggle();
            return;
        }
        switch (phase) {
            case DUPE -> {
                int n = amountToFill();
                if (n <= 0) { phase = Phase.THROW; return; } // already full: just throw
                before = count();
                mc.getConnection().sendCommand(command.get().replace("{n}", String.valueOf(Math.min(n, maxN.getInt()))).replaceFirst("^/", ""));
                phase = Phase.WAIT;
                wait = 20; // give up waiting after a second
            }
            case WAIT -> phase = Phase.THROW; // reached when the wait ran out (no items came)
            case THROW -> {
                if (up.get()) {
                    Prism.rotations().request(mc.player.getYRot(), -90f, 80);
                    if (Prism.rotations().getServerPitch() > -80f) return;
                }
                int sent = 0;
                for (int i = 0; i < 36 && sent < throwsPerTick.getInt(); i++) {
                    if (i == heldSlot || !same(mc.player.getInventory().getItem(i))) continue;
                    click(InvUtil.toScreenSlot(i), 1, ContainerInput.THROW); // ctrl+Q: the whole stack
                    sent++;
                }
                if (sent == 0) phase = Phase.KEEP_ONE;
            }
            case KEEP_ONE -> {
                ItemStack held = mc.player.getInventory().getItem(heldSlot);
                if (same(held) && held.getCount() > 1) {
                    if (up.get()) {
                        Prism.rotations().request(mc.player.getYRot(), -90f, 80);
                        if (Prism.rotations().getServerPitch() > -80f) return;
                    }
                    int slot = InvUtil.toScreenSlot(heldSlot);
                    click(slot, 0, ContainerInput.PICKUP);   // take the stack
                    click(slot, 1, ContainerInput.PICKUP);   // right click: put one back
                    click(-999, 0, ContainerInput.PICKUP);   // click outside: throw the rest
                }
                cycles++;
                phase = Phase.PAUSE;
                wait = cycleDelay.getInt();
            }
            case PAUSE -> phase = Phase.DUPE;
        }
    }

    @Override
    public void onPostTick() {
        // the dupe landed: throw right away instead of waiting out the timeout
        if (phase == Phase.WAIT && count() > before) { phase = Phase.THROW; wait = 0; }
    }

    private void click(int screenSlot, int button, ContainerInput input) {
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, screenSlot, button, input, mc.player);
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
}
