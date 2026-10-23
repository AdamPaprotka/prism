package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.HashSet;
import java.util.Set;

/**
 * Right-clicks on the listed blocks / entities do nothing, so a misclick in a fight doesn't open a crafting table,
 * sleep in a bed or trade with a villager. With PlaceInstead, clicking a listed block with a block in hand places
 * against it as if you were sneaking (InteractionManagerMixin).
 */
public class NoInteract extends Module {
    public final ListSetting blocks = list("Blocks", "crafting_table,anvil,chipped_anvil,damaged_anvil,enchanting_table,furnace,smoker,blast_furnace,ender_chest,respawn_anchor,lever,stonecutter,grindstone,loom,cartography_table,smithing_table,note_block",
        "Blocks that ignore right-click.", ListSetting.Kind.BLOCK);
    public final BoolSetting beds = bool("Beds", true, "Also every bed (the list takes exact ids).");
    public final BoolSetting placeInstead = bool("PlaceInstead", true, "Holding a block: place it against the listed block instead.");
    public final BoolSetting villagers = bool("Villagers", true, "Ignore right-clicking villagers and wandering traders.");
    public final BoolSetting players = bool("Players", false, "Ignore right-clicking players.");
    public final BoolSetting frames = bool("ItemFrames", false, "Ignore right-clicking item frames (they rotate the item).");

    private Set<Block> parsed = Set.of();
    private String lastList = "";

    public NoInteract() { super("NoInteract", "Right-clicks on chosen blocks/entities do nothing.", Category.PLAYER); }

    public boolean blocks(BlockState state) {
        if (!lastList.equals(blocks.get())) {
            lastList = blocks.get();
            Set<Block> s = new HashSet<>();
            for (String id : lastList.split(",")) {
                Identifier i = Identifier.tryParse(id.trim().contains(":") ? id.trim() : "minecraft:" + id.trim());
                if (i != null && BuiltInRegistries.BLOCK.containsKey(i)) s.add(BuiltInRegistries.BLOCK.getValue(i));
            }
            parsed = s;
        }
        return parsed.contains(state.getBlock()) || (beds.get() && state.is(net.minecraft.tags.BlockTags.BEDS));
    }

    public boolean blocks(Entity e) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return (villagers.get() && (type.equals("villager") || type.equals("wandering_trader")))
            || (players.get() && e instanceof Player)
            || (frames.get() && (type.equals("item_frame") || type.equals("glow_item_frame")));
    }
}
