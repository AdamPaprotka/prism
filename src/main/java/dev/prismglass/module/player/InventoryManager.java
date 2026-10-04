package dev.prismglass.module.player;

import net.minecraft.core.BlockPos;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.combat.AutoArmor;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Blocks;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Hotbar layout + inventory tidying, never drops anything.
 *
 * <p>Layout lists one category per hotbar slot (1-9), "-" leaves a slot free. For every slot the best item of
 * its category anywhere in the inventory is swapped in (one SWAP click). Partial stacks are merged.
 * Categories: sword axe pickaxe shovel bow crossbow trident mace shield gapple pearl totem crystal xp obsidian
 * water blocks food firework.
 */
public class InventoryManager extends Module {
    public final ListSetting layout = choices("Layout", "sword,axe,gapple,pearl,crystal,obsidian,totem,pickaxe,blocks",
        "Hotbar slots in order, one category per slot; - = leave free.", true, 9,
        "sword", "axe", "pickaxe", "shovel", "bow", "crossbow", "trident", "mace", "shield", "gapple", "pearl", "totem",
        "crystal", "xp", "obsidian", "water", "firework", "food", "blocks", "-")
        .icons("sword", "netherite_sword", "axe", "netherite_axe", "pickaxe", "netherite_pickaxe", "shovel", "netherite_shovel",
            "bow", "bow", "crossbow", "crossbow", "trident", "trident", "mace", "mace", "shield", "shield",
            "gapple", "enchanted_golden_apple", "pearl", "ender_pearl", "totem", "totem_of_undying", "crystal", "end_crystal",
            "xp", "experience_bottle", "obsidian", "obsidian", "water", "water_bucket", "firework", "firework_rocket",
            "food", "cooked_beef", "blocks", "cobblestone");
    public final NumberSetting delay = num("Delay", 120, 0, 1000, 10, "ms between moves.");
    public final BoolSetting merge = bool("MergeStacks", true, "Merge partial stacks.");
    public final BoolSetting onlyInventory = bool("OnlyInInventory", false, "Only while your inventory screen is open.");
    private final Timer timer = new Timer();

    public InventoryManager() { super("InventoryManager", "Sorts your hotbar by layout and tidies stacks (no dropping).", Category.PLAYER); }

    @Override
    public void onTick() {
        boolean invOpen = mc.screen instanceof InventoryScreen;
        if ((mc.screen != null && !invOpen) || (onlyInventory.get() && !invOpen)) return;
        if (!timer.passed(delay.get()) || mc.player.isUsingItem()) return;
        if (sortStep() || (merge.get() && mergeStep())) timer.reset();
    }

    private String[] cats() {
        String[] parts = layout.get().toLowerCase(Locale.ROOT).split(",");
        String[] out = new String[9];
        for (int i = 0; i < 9; i++) out[i] = i < parts.length ? parts[i].trim() : "-";
        return out;
    }

    private boolean sortStep() {
        String[] cats = cats();
        for (int hot = 0; hot < 9; hot++) {
            ToDoubleFunction<ItemStack> score = scorer(cats[hot]);
            if (score == null) continue;
            double current = score.applyAsDouble(mc.player.getInventory().getItem(hot));
            int best = -1;
            double bestScore = current;
            for (int i = 0; i < 36; i++) {
                if (i == hot) continue;
                // don't steal from a hotbar slot that is itself designated for this category
                if (i < 9 && cats[i].equals(cats[hot])) continue;
                double sc = score.applyAsDouble(mc.player.getInventory().getItem(i));
                if (sc > bestScore + 1e-6) { bestScore = sc; best = i; }
            }
            if (best == -1) continue;
            if (!InvUtil.canClick()) return false;
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, InvUtil.toScreenSlot(best), hot,
                ContainerInput.SWAP, mc.player);
            return true;
        }
        return false;
    }

    private boolean mergeStep() {
        for (int a = 9; a < 36; a++) {
            ItemStack sa = mc.player.getInventory().getItem(a);
            if (sa.isEmpty() || !sa.isStackable() || sa.getCount() >= sa.getMaxStackSize()) continue;
            for (int b = a + 1; b < 36; b++) {
                ItemStack sb = mc.player.getInventory().getItem(b);
                if (ItemStack.isSameItemSameComponents(sa, sb) && sb.getCount() < sb.getMaxStackSize()) {
                    if (!InvUtil.canClick()) return false;
                    InvUtil.move(InvUtil.toScreenSlot(b), InvUtil.toScreenSlot(a));
                    return true;
                }
            }
        }
        return false;
    }

    /** Score of an item for a category (higher = better, <= 0 = doesn't belong). */
    private static ToDoubleFunction<ItemStack> scorer(String cat) {
        return switch (cat) {
            case "sword" -> s -> s.is(ItemTags.SWORDS) ? CombatUtil.attackDamage(s) + ench(s, "sharpness") * 0.6 : 0;
            case "axe" -> s -> s.getItem() instanceof AxeItem ? CombatUtil.attackDamage(s) + s.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) * 0.1 : 0;
            case "pickaxe" -> s -> s.is(ItemTags.PICKAXES) ? s.getDestroySpeed(Blocks.STONE.defaultBlockState()) + ench(s, "efficiency") : 0;
            case "shovel" -> s -> s.is(ItemTags.SHOVELS) ? s.getDestroySpeed(Blocks.DIRT.defaultBlockState()) + ench(s, "efficiency") : 0;
            case "bow" -> s -> s.getItem() instanceof BowItem ? 1 + ench(s, "power") : 0;
            case "crossbow" -> s -> s.getItem() instanceof CrossbowItem ? 1 + ench(s, "quick_charge") : 0;
            case "trident" -> s -> s.getItem() instanceof TridentItem ? 1 + ench(s, "loyalty") + ench(s, "riptide") : 0;
            case "mace" -> s -> s.getItem() instanceof MaceItem ? 1 + ench(s, "density") : 0;
            case "shield" -> s -> s.getItem() instanceof ShieldItem ? 1 + s.getCount() : 0;
            case "gapple" -> s -> s.is(Items.ENCHANTED_GOLDEN_APPLE) ? 2 + s.getCount() / 100.0 : s.is(Items.GOLDEN_APPLE) ? 1 + s.getCount() / 100.0 : 0;
            case "pearl" -> s -> count(s, Items.ENDER_PEARL);
            case "totem" -> s -> count(s, Items.TOTEM_OF_UNDYING);
            case "crystal" -> s -> count(s, Items.END_CRYSTAL);
            case "xp" -> s -> count(s, Items.EXPERIENCE_BOTTLE);
            case "obsidian" -> s -> count(s, Items.OBSIDIAN);
            case "water" -> s -> s.is(Items.WATER_BUCKET) ? 1 : 0;
            case "firework" -> s -> count(s, Items.FIREWORK_ROCKET);
            case "food" -> s -> {
                var food = s.get(DataComponents.FOOD);
                if (food == null || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ROTTEN_FLESH)) return 0;
                return food.saturation() + s.getCount() / 100.0;
            };
            case "blocks" -> s -> s.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(mc.level, net.minecraft.core.BlockPos.ZERO)
                && !bi.getBlock().defaultBlockState().hasBlockEntity() && !s.is(Items.OBSIDIAN) ? s.getCount() : 0;
            default -> null;
        };
    }

    private static double count(ItemStack s, Item item) { return s.is(item) ? s.getCount() : 0; }

    private static int ench(ItemStack s, String id) {
        var e = s.get(DataComponents.ENCHANTMENTS);
        if (e == null) return 0;
        for (var entry : e.keySet()) {
            if (entry.unwrapKey().map(k -> k.identifier().getPath().equals(id)).orElse(false)) return e.getLevel(entry);
        }
        return 0;
    }
}
