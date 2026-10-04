package dev.prismglass.module.world;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import java.util.HashSet;
import java.util.Set;

public class AutoBreed extends Module {
    public final NumberSetting range = num("Range", 4, 1, 6, 0.5, "Interact range.");
    private final Set<Integer> fed = new HashSet<>();
    private int ticks;

    public AutoBreed() { super("AutoBreed", "Feeds animals so they breed.", Category.WORLD); }

    @Override
    public void onTick() {
        if (++ticks % 4 != 0) return;
        if (ticks % 1200 == 0) fed.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Animal a) || a.isBaby() || !a.canFallInLove() || fed.contains(e.getId())) continue;
            if (mc.player.distanceTo(e) > range.get() || !a.isFood(mc.player.getMainHandItem())) continue;
            if (!Prism.guard().canInteractEntity(e.getId())) return;
            mc.gameMode.interact(mc.player, e, new net.minecraft.world.phys.EntityHitResult(e), InteractionHand.MAIN_HAND);
            fed.add(e.getId());
            return;
        }
    }
}
