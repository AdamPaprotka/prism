package dev.prismglass.module.render;

import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/** Hover a shulker box in any inventory or chest: its 27 slots show as a glass grid next to the cursor. */
public class ShulkerPeek extends Module {
    public ShulkerPeek() { super("ShulkerPeek", "See what's inside a shulker box when you hover it.", Category.RENDER); }

    /** Called by the container screen after it drew (ContainerScreenMixin). */
    public static void draw(GuiGraphicsExtractor ctx, int mouseX, int mouseY, ItemStack stack) {
        ShulkerPeek m = Prism.modules().get(ShulkerPeek.class);
        if (m == null || !m.isEnabled() || stack.isEmpty()) return;
        if (!(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) return;
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return;
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(items);

        int w = 9 * 18 + 8, h = 3 * 18 + 8;
        int x = Math.min(mouseX + 10, ctx.guiWidth() - w - 2), y = Math.max(2, mouseY - h - 10);
        ctx.nextStratum(); // above the vanilla tooltip
        Glass.panel(ctx, x, y, w, h, Prism.modules().get(ClickGui.class).panelStyle());
        for (int i = 0; i < 27; i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            int ix = x + 4 + (i % 9) * 18 + 1, iy = y + 4 + (i / 9) * 18 + 1;
            ctx.item(s, ix, iy);
            ctx.itemDecorations(net.minecraft.client.Minecraft.getInstance().font, s, ix, iy);
        }
    }
}
