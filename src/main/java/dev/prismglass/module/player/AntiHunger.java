package dev.prismglass.module.player;

import net.minecraft.client.multiplayer.chat.ChatRestriction.Action;

import dev.prismglass.Prism;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/** Less hunger: hides sprint state and ground state from the server. [Grim-unsafe] */
public class AntiHunger extends Module {
    public final BoolSetting sprint = bool("Sprint", true, "Don't tell the server you sprint.");
    public final BoolSetting ground = bool("Ground", false, "Spoof not-on-ground (no jump exhaustion).");

    public AntiHunger() { super("AntiHunger", "Lose hunger slower. [Grim-unsafe]", Category.PLAYER); }

    @Override
    public void onPacketSend(PacketEvent e) {
        if (sprint.get() && e.packet instanceof ServerboundPlayerCommandPacket c && c.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) e.cancel();
        if (ground.get() && e.packet instanceof ServerboundMovePlayerPacket p && mc.player.fallDistance <= 0 && !mc.gameMode.isDestroying()) p.onGround = false;
    }
}
