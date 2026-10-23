package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.Hud;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * Drops anvils on an enemy's head. A falling anvil deals 2 damage per block fallen (up to 40, armour applies), so
 * Height trades setup time for damage. It needs something to be placed against up there: the enemy's trap or a
 * support chain built from it (never in their column, that would stop the drop). With PlaceButton a button goes
 * in their feet: an anvil landing on a button drops as an item instead of becoming a block, so the next one falls
 * all the way again instead of stacking.
 */
public class AutoAnvil extends Module {
    public final NumberSetting range = num("Range", 5, 2, 6, 0.5, "Target range.");
    public final NumberSetting height = num("Height", 3, 2, 6, 1, "Blocks above their head to drop from (lower when that's out of reach).");
    public final NumberSetting delay = num("Delay", 10, 0, 40, 1, "Ticks between anvils.");
    public final NumberSetting supports = num("Supports", 2, 0, 4, 1, "Support blocks it may place to hold the anvil.");
    public final BoolSetting onlyHoled = bool("OnlyInHole", true, "Only targets standing in a hole (they can't step out).");
    public final BoolSetting placeButton = bool("PlaceButton", true, "Put a button in their feet so anvils don't stack up.");

    private int cooldown;
    /** Anvils placed since enabled. */
    public int dropped;
    private Player target;

    public AutoAnvil() { super("AutoAnvil", "Drops anvils on trapped enemies' heads.", Category.COMBAT); }

    @Override public void onEnable() { dropped = 0; }

    @Override public void onDisable() { target = null; }

    @Override
    public void onTick() {
        if (cooldown > 0) cooldown--;
        target = null;
        double best = range.get() + 1;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || p.isCreative() || Prism.friends().isFriend(p)) continue;
            double d = mc.player.distanceTo(p);
            if (d > range.get() || (onlyHoled.get() && !EntityUtil.isInHole(p))) continue;
            if (d < best) { best = d; target = p; }
        }
        if (target == null) return;
        Hud.setTarget(target);
        BlockPos feet = target.blockPosition();

        if (placeButton.get() && BlockUtil.isReplaceable(feet) && !BlockUtil.state(feet).is(BlockTags.BUTTONS)) {
            int button = InvUtil.findHotbar(s -> s.is(ItemTags.BUTTONS));
            // the button sits on their floor block; standing in it doesn't stop it being placed (no collision box)
            if (button != -1 && BlockUtil.click(feet.below(), net.minecraft.core.Direction.UP, button, true, 85) != BlockUtil.Result.FAILED) return;
        }
        if (cooldown > 0) return;

        int anvil = InvUtil.findHotbar(s -> s.is(ItemTags.ANVIL));
        if (anvil == -1) return;
        int block = BlockUtil.findBlock(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.COBBLESTONE, Blocks.NETHERRACK);
        // highest drop we can reach: a shorter drop does less damage, but no drop does none
        for (int h = height.getInt(); h >= 2; h--) {
            BlockPos drop = feet.above(h + 1);
            boolean clear = true;
            for (BlockPos q = feet.above(2); q.getY() < drop.getY(); q = q.above()) if (!BlockUtil.isReplaceable(q)) { clear = false; break; }
            if (!clear) continue; // roofed below this height
            List<BlockPos> chain = BlockUtil.supportChain(drop, supports.getInt(), q -> q.getX() != feet.getX() || q.getZ() != feet.getZ());
            if (chain.isEmpty()) continue;
            BlockPos next = chain.get(0);
            BlockUtil.Result r;
            if (next.equals(drop)) {
                r = BlockUtil.place(drop, anvil, true, 85);
                if (r == BlockUtil.Result.PLACED) { cooldown = delay.getInt(); dropped++; }
            } else {
                if (block == -1) continue;
                r = BlockUtil.place(next, block, true, 85);
            }
            if (r != BlockUtil.Result.FAILED) return;
        }
    }

    @Override public String getInfo() { return target == null ? null : target.getName().getString(); }
}
