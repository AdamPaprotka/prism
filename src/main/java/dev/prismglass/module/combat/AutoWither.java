package dev.prismglass.module.combat;

import dev.prismglass.Prism;
import dev.prismglass.manager.MovementHooks;
import dev.prismglass.manager.WalkGoal;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a wither in front of you: 4 soul sand / soul soil in a T (base, middle, both arms) and 3 wither skeleton
 * skulls on top, one block per tick, middle skull last (that's the one that spawns it). The T is built across your
 * view, Distance blocks ahead. Checks the space and your items first and turns off when the wither spawns. With
 * StrictDirection it moves like a person building it: jumps for the skulls and walks round for the far arm.
 */
public class AutoWither extends Module implements WalkGoal {
    public final NumberSetting distance = num("Distance", 3, 2, 4, 1, "Blocks in front of you.");

    private BlockPos base;
    private Direction side, facing;
    /** Where to stand to see the face the next block goes against (StrictDirection), or null. */
    private Vec3 walkTo;

    public AutoWither() { super("AutoWither", "Builds a wither in front of you.", Category.COMBAT); }

    @Override
    public void onEnable() {
        base = null;
        if (nullCheck()) return;
        facing = mc.player.getDirection();
        side = facing.getClockWise();
        walkTo = null;
        base = BlockUtil.playerPos().relative(facing, distance.getInt());
        for (BlockPos p : layout()) {
            if (!BlockUtil.isReplaceable(p)) { ChatUtil.error("AutoWither: no room in front of you"); setEnabled(false); return; }
        }
        if (!BlockUtil.state(base.below()).isFaceSturdy(mc.level, base.below(), Direction.UP)) { ChatUtil.error("AutoWither: needs solid ground"); setEnabled(false); return; }
        int sand = InvUtil.count(Items.SOUL_SAND) + InvUtil.count(Items.SOUL_SOIL);
        int skulls = InvUtil.count(Items.WITHER_SKELETON_SKULL);
        if (sand < 4 || skulls < 3) { ChatUtil.error("AutoWither: need 4 soul sand/soil and 3 wither skulls (hotbar)"); setEnabled(false); }
    }

    /**
     * StrictDirection: only faces we can see may be clicked. A top face above our eye (the skulls go on blocks two
     * high) is reached with a jump; an arm goes against a side of the middle block that faces away from us, so we
     * walk round to stand beyond that arm, one block toward where we started.
     */
    private void reposition(BlockPos p) {
        BlockPos below = p.below();
        if (!BlockUtil.isReplaceable(below)) {
            if (mc.player.getEyeY() <= below.getY() + 1 && mc.player.onGround()) MovementHooks.requestJump();
            return;
        }
        for (Direction d : BlockUtil.HORIZONTALS) {
            if (BlockUtil.isReplaceable(p.relative(d))) continue;
            BlockPos stand = p.relative(d.getOpposite()).relative(facing.getOpposite());
            walkTo = new Vec3(stand.getX() + 0.5, base.getY(), stand.getZ() + 0.5);
            return;
        }
    }

    @Override public Vec3 walkPoint() { return isEnabled() ? walkTo : null; }
    @Override public boolean walkSprint() { return false; }

    /** Placement order: body, then skulls with the middle one last. */
    private List<BlockPos> layout() {
        BlockPos mid = base.above();
        return List.of(base, mid, mid.relative(side), mid.relative(side.getOpposite()),
            mid.above().relative(side), mid.above().relative(side.getOpposite()), mid.above());
    }

    @Override
    public void onTick() {
        if (base == null) { setEnabled(false); return; }
        List<BlockPos> order = layout();
        for (int i = 0; i < order.size(); i++) {
            BlockPos p = order.get(i);
            if (!BlockUtil.isReplaceable(p)) continue;
            int slot = i < 4 ? BlockUtil.findBlock(Blocks.SOUL_SAND, Blocks.SOUL_SOIL) : InvUtil.findHotbar(Items.WITHER_SKELETON_SKULL);
            if (slot == -1) { ChatUtil.error("AutoWither: ran out of " + (i < 4 ? "soul sand" : "skulls")); setEnabled(false); return; }
            if (BlockUtil.place(p, slot, true, 60) != BlockUtil.Result.FAILED) { walkTo = null; return; }
            if (Prism.anticheat().strictDirection.get()) reposition(p);
            return;
        }
        // every spot filled: the last skull spawns it and the blocks vanish
        ChatUtil.good("AutoWither: built");
        setEnabled(false);
    }
}
