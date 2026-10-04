package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Items;

public class AutoShearer extends Module {
    public final NumberSetting range = num("Range", 4, 1, 6, 0.5, "Interact range.");

    public AutoShearer() { super("AutoShearer", "Shears nearby sheep.", Category.WORLD); }

    @Override
    public void onTick() {
        if (!mc.player.getMainHandItem().is(Items.SHEARS)) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Sheep s) || !s.readyForShearing() || mc.player.distanceTo(e) > range.get()) continue;
            if (!Prism.guard().canInteractEntity(e.getId())) return;
            mc.gameMode.interact(mc.player, e, new net.minecraft.world.phys.EntityHitResult(e), InteractionHand.MAIN_HAND);
            return;
        }
    }
}
