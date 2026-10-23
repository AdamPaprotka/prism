package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

/**
 * Mine through entities: while holding a mining tool the crosshair ignores entities (item frames, armour stands,
 * mobs in a tunnel...) and lands on the block behind. The server only checks the block's distance, so this is just
 * a normal dig on a block you can reach.
 */
public class NoMiningTrace extends Module {
    public final ModeSetting when = mode("When", "Tools", "Pickaxe = pickaxes only. Tools = pickaxe/axe/shovel/hoe. Always = whatever you hold.", "Pickaxe", "Tools", "Always");
    public final BoolSetting living = bool("Living", false, "Also look through mobs and players (off = only item frames, armour stands, boats, minecarts, paintings...).");

    public NoMiningTrace() { super("NoMiningTrace", "Mine blocks through entities while holding a tool.", Category.PLAYER); }

    /** Whether the crosshair should look through entities right now. */
    public boolean active() {
        if (mc.player == null) return false;
        ItemStack s = mc.player.getMainHandItem();
        return switch (when.get()) {
            case "Pickaxe" -> s.is(ItemTags.PICKAXES);
            case "Tools" -> s.is(ItemTags.PICKAXES) || s.is(ItemTags.AXES) || s.is(ItemTags.SHOVELS) || s.is(ItemTags.HOES);
            default -> true;
        };
    }

    public boolean ignores(Entity e) {
        if (e instanceof ArmorStand) return true;
        return living.get() || !(e instanceof LivingEntity);
    }
}
