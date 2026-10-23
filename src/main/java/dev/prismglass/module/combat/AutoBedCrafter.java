package dev.prismglass.module.combat;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Keeps you stocked for BedAura: when you're under Refill beds and carry wool + planks, it opens the nearest crafting
 * table in reach (or places one from your hotbar), crafts beds through the recipe book until you're at Target or out
 * of materials, and closes it again. Opening and placing go through the normal visible-face click with rotation.
 */
public class AutoBedCrafter extends Module {
    public final NumberSetting refill = num("Refill", 2, 0, 36, 1, "Start crafting when you have fewer beds than this.");
    public final NumberSetting target = num("Target", 8, 1, 64, 1, "Stop once you have this many.");
    public final BoolSetting open = bool("Open", true, "Open a crafting table in reach by itself.");
    public final BoolSetting place = bool("Place", true, "No table around: place one from your hotbar.");
    public final BoolSetting onlySafe = bool("OnlySafe", false, "Only when no enemy is within 8 blocks.");

    private boolean opened, crafting;
    private int wait, clickWait;

    public AutoBedCrafter() { super("AutoBedCrafter", "Crafts beds from wool + planks when you run low (opens/places a table).", Category.COMBAT); }

    @Override public void onDisable() { if (opened && mc.player != null && mc.screen != null) mc.player.closeContainer(); opened = crafting = false; }

    private int beds() {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem() instanceof BedItem) n += inv.getItem(i).getCount();
        return n;
    }

    private boolean haveMaterials() {
        var inv = mc.player.getInventory();
        int planks = 0;
        java.util.Map<net.minecraft.world.item.Item, Integer> wool = new java.util.HashMap<>();
        for (int i = 0; i < 36; i++) {
            var s = inv.getItem(i);
            if (s.is(ItemTags.PLANKS)) planks += s.getCount();
            if (s.is(ItemTags.WOOL)) wool.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return planks >= 3 && wool.values().stream().anyMatch(c -> c >= 3);
    }

    @Override
    public void onTick() {
        if (wait > 0) { wait--; return; }
        int beds = beds();
        var grid = CraftUtil.grid();
        if (grid instanceof CraftingMenu) {
            if (!crafting && !opened) return; // the player opened it: leave them alone unless we're low
            if (!grid.getResultSlot().getItem().isEmpty() && grid.getResultSlot().getItem().getItem() instanceof BedItem) {
                CraftUtil.take(grid, grid.getResultSlot().index, false);
                wait = 2;
                return;
            }
            var recipe = beds < target.getInt() ? CraftUtil.find(grid, s -> s.getItem() instanceof BedItem) : null;
            if (recipe == null) { finish(); return; }
            CraftUtil.place(grid, recipe, target.getInt() - beds > 1);
            wait = 2;
            return;
        }
        if (mc.screen != null) return;
        crafting = opened = false;
        if (beds >= refill.getInt() || !haveMaterials() || !open.get()) return;
        if (onlySafe.get() && EntityUtil.closestEnemy(8) != null) return;
        if (clickWait > 0) { clickWait--; return; }
        BlockPos table = nearestTable();
        if (table == null) {
            if (!place.get()) return;
            int slot = InvUtil.findHotbar(Items.CRAFTING_TABLE);
            if (slot == -1) return;
            BlockPos spot = placeSpot();
            if (spot != null) BlockUtil.place(spot, slot, true, 50);
            clickWait = 4;
            return;
        }
        Direction face = BlockUtil.breakFace(table);
        if (face == null) return;
        if (BlockUtil.click(table, face, mc.player.getInventory().getSelectedSlot(), false, 50) == BlockUtil.Result.PLACED) {
            opened = crafting = true;
            clickWait = 10;
        }
    }

    private void finish() {
        if (opened) mc.player.closeContainer();
        opened = crafting = false;
        wait = 10;
    }

    private BlockPos nearestTable() {
        int r = (int) Math.ceil(dev.prismglass.Prism.anticheat().placeRange.get());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(mc.player.blockPosition().offset(-r, -r, -r), mc.player.blockPosition().offset(r, r + 1, r))) {
            if (!BlockUtil.state(p).is(Blocks.CRAFTING_TABLE) || !BlockUtil.inPlaceRange(p) || BlockUtil.breakFace(p) == null) continue;
            double d = mc.player.getEyePosition().distanceToSqr(p.getCenter());
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    /** A free spot next to you (not where you stand) that something can be placed against. */
    private BlockPos placeSpot() {
        BlockPos me = mc.player.blockPosition();
        for (Direction d : BlockUtil.HORIZONTALS) {
            BlockPos p = me.relative(d);
            if (BlockUtil.canPlace(p) && BlockUtil.getPlaceSide(p) != null) return p;
        }
        return null;
    }

    @Override public String getInfo() { return crafting ? "Crafting" : null; }
}
