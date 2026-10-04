package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

public class AutoFish extends Module {
    public final NumberSetting recastDelay = num("RecastDelay", 15, 2, 60, 1, "Ticks before casting again.");
    private int recastIn = -1;

    public AutoFish() { super("AutoFish", "Catches fish for you.", Category.PLAYER); }

    @Override
    public void onPacketReceive(PacketEvent e) {
        if (!(e.packet instanceof ClientboundSoundPacket p) || mc.player == null || mc.player.fishing == null) return;
        if (!p.getSound().value().equals(SoundEvents.FISHING_BOBBER_SPLASH)) return;
        if (mc.player.fishing.distanceToSqr(p.getX(), p.getY(), p.getZ()) > 4) return;
        mc.execute(() -> {
            if (mc.player != null && mc.player.getMainHandItem().is(Items.FISHING_ROD)) {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                recastIn = recastDelay.getInt();
            }
        });
    }

    @Override
    public void onTick() {
        if (recastIn > 0 && --recastIn == 0 && mc.player.getMainHandItem().is(Items.FISHING_ROD) && mc.player.fishing == null) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
    }
}
