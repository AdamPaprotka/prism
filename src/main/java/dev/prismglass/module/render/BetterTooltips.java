package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.NumberSetting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Extra lines on item tooltips (durability, food, anvil uses, bees, shulker summary...) and an ender chest preview:
 * the echest's contents are remembered whenever you open one, and hovering an ender chest item shows them.
 */
public class BetterTooltips extends Module {
    public final BoolSetting durability = bool("Durability", true, "Exact durability left, coloured by how worn it is.");
    public final BoolSetting food = bool("Food", true, "Hunger and saturation a food gives.");
    public final BoolSetting shulker = bool("ShulkerSummary", true, "Text list of what's in a shulker (most first). ShulkerPeek shows the grid.");
    public final NumberSetting shulkerLines = num("SummaryLines", 5, 1, 12, 1, "How many item types the shulker summary lists.");
    public final BoolSetting echest = bool("EChestPreview", true, "Hovering an ender chest shows its contents (from the last time you opened one).");
    public final BoolSetting carried = bool("CarriedCount", true, "How many of this item you have in your inventory.");
    public final BoolSetting repair = bool("AnvilUses", true, "Anvil repair cost the item has built up.");
    public final BoolSetting bees = bool("Bees", true, "Bees inside a beehive or bee nest.");
    public final BoolSetting mapId = bool("MapId", true, "Map number.");
    public final BoolSetting itemId = bool("ItemId", false, "Registry id (minecraft:...) even without F3+H.");

    /** Last seen ender chest, 27 slots; null until you open one this session. */
    private static NonNullList<ItemStack> echestItems;
    private static boolean hooked;

    public BetterTooltips() {
        super("BetterTooltips", "More info on item tooltips + ender chest preview.", Category.RENDER);
        if (!hooked) {
            hooked = true;
            ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
                BetterTooltips m = Prism.modules() == null ? null : Prism.modules().get(BetterTooltips.class);
                if (m != null && m.isEnabled() && !stack.isEmpty()) m.addLines(stack, lines);
            });
        }
    }

    @Override
    public void onTick() {
        // remember the ender chest while it's open (its title is the translatable "container.enderchest")
        if (mc.gui.screen() instanceof ContainerScreen cs && cs.getTitle().equals(Component.translatable("container.enderchest"))) {
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            for (int i = 0; i < 27 && i < cs.getMenu().slots.size(); i++) items.set(i, cs.getMenu().getSlot(i).getItem().copy());
            echestItems = items;
        }
    }

    private void addLines(ItemStack stack, List<Component> lines) {
        List<Component> extra = new ArrayList<>();

        if (durability.get() && stack.isDamageableItem()) {
            int max = stack.getMaxDamage(), left = max - stack.getDamageValue();
            float f = left / (float) max;
            ChatFormatting c = f > 0.6f ? ChatFormatting.GREEN : f > 0.3f ? ChatFormatting.YELLOW : f > 0.1f ? ChatFormatting.GOLD : ChatFormatting.RED;
            extra.add(gray("Durability: ").append(Component.literal(left + "/" + max + " (" + Math.round(f * 100) + "%)").withStyle(c)));
        }

        FoodProperties fp = stack.get(DataComponents.FOOD);
        if (food.get() && fp != null) {
            extra.add(gray("Food: ").append(Component.literal("+" + fp.nutrition() + " hunger, +"
                + String.format(java.util.Locale.ROOT, "%.1f", fp.saturation()) + " saturation").withStyle(ChatFormatting.GOLD)));
        }

        if (shulker.get() && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) {
                NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
                contents.copyInto(items);
                summarize(items, extra);
            }
        }

        if (echest.get() && stack.is(Items.ENDER_CHEST)) {
            if (echestItems == null) extra.add(gray("Open an ender chest once to see its contents here"));
            else {
                int used = 0;
                for (ItemStack s : echestItems) if (!s.isEmpty()) used++;
                extra.add(gray("Ender chest: ").append(Component.literal(used + "/27 slots used").withStyle(ChatFormatting.LIGHT_PURPLE)));
            }
        }

        Integer cost = stack.get(DataComponents.REPAIR_COST);
        if (repair.get() && cost != null && cost > 0) {
            int uses = 32 - Integer.numberOfLeadingZeros(cost + 1) - 1; // cost = 2^uses - 1
            extra.add(gray("Anvil uses: ").append(Component.literal(uses + " (+" + cost + " levels)").withStyle(uses >= 5 ? ChatFormatting.RED : ChatFormatting.AQUA)));
        }

        var beeData = stack.get(DataComponents.BEES);
        if (bees.get() && beeData != null) {
            extra.add(gray("Bees: ").append(Component.literal(String.valueOf(beeData.bees().size())).withStyle(ChatFormatting.YELLOW)));
        }

        var map = stack.get(DataComponents.MAP_ID);
        if (mapId.get() && map != null) extra.add(gray("Map #").append(Component.literal(String.valueOf(map.id())).withStyle(ChatFormatting.WHITE)));

        if (carried.get() && mc.player != null && stack.getMaxStackSize() > 1) {
            int n = 0;
            Item item = stack.getItem();
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
            if (n > 0) extra.add(gray("Carrying: ").append(Component.literal(n + (n >= 64 ? " (" + fmtStacks(n, stack.getMaxStackSize()) + ")" : "")).withStyle(ChatFormatting.WHITE)));
        }

        if (itemId.get()) extra.add(Component.literal(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).withStyle(ChatFormatting.DARK_GRAY));

        lines.addAll(extra);
    }

    private void summarize(NonNullList<ItemStack> items, List<Component> out) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        Map<Item, ItemStack> first = new LinkedHashMap<>();
        for (ItemStack s : items) {
            if (s.isEmpty()) continue;
            counts.merge(s.getItem(), s.getCount(), Integer::sum);
            first.putIfAbsent(s.getItem(), s);
        }
        if (counts.isEmpty()) { out.add(gray("Empty")); return; }
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        int shown = Math.min(sorted.size(), shulkerLines.getInt());
        for (int i = 0; i < shown; i++) {
            var e = sorted.get(i);
            out.add(Component.literal(" " + e.getValue() + "x ").withStyle(ChatFormatting.WHITE).append(first.get(e.getKey()).getHoverName().copy().withStyle(ChatFormatting.GRAY)));
        }
        if (sorted.size() > shown) out.add(gray(" and " + (sorted.size() - shown) + " more..."));
    }

    private static String fmtStacks(int n, int stackSize) {
        int stacks = n / stackSize, rest = n % stackSize;
        return stacks + "x" + stackSize + (rest > 0 ? " + " + rest : "");
    }

    private static MutableComponent gray(String s) { return Component.literal(s).withStyle(ChatFormatting.GRAY); }

    /** Echest grid next to the cursor when hovering an ender chest item (ContainerScreenMixin). */
    public static void draw(GuiGraphicsExtractor ctx, int mouseX, int mouseY, ItemStack stack) {
        BetterTooltips m = Prism.modules().get(BetterTooltips.class);
        if (m == null || !m.isEnabled() || !m.echest.get() || echestItems == null || !stack.is(Items.ENDER_CHEST)) return;
        if (mc.gui.screen() instanceof ContainerScreen cs && cs.getTitle().equals(Component.translatable("container.enderchest"))) return;
        int w = 9 * 18 + 8, h = 3 * 18 + 8;
        int x = Math.min(mouseX + 10, ctx.guiWidth() - w - 2), y = Math.max(2, mouseY - h - 10);
        ctx.nextStratum();
        Glass.panel(ctx, x, y, w, h, Prism.modules().get(ClickGui.class).panelStyle());
        for (int i = 0; i < 27; i++) {
            ItemStack s = echestItems.get(i);
            if (s.isEmpty()) continue;
            int ix = x + 4 + (i % 9) * 18 + 1, iy = y + 4 + (i / 9) * 18 + 1;
            ctx.item(s, ix, iy);
            ctx.itemDecorations(mc.font, s, ix, iy);
        }
    }
}
