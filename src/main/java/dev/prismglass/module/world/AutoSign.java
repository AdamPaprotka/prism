package dev.prismglass.module.world;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.mixin.SignEditAccessor;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.SignBlock;

/**
 * Writes your text on signs you place: when the sign editor opens right after you placed a sign, the four lines
 * are filled in and the editor closes, which sends the normal sign update packet (the same one Done sends).
 * The text is never logged.
 */
public class AutoSign extends Module {
    public final TextSetting line1 = text("Line1", "", "First line.");
    public final TextSetting line2 = text("Line2", "", "Second line.");
    public final TextSetting line3 = text("Line3", "", "Third line.");
    public final TextSetting line4 = text("Line4", "", "Fourth line.");
    public final BoolSetting onlyPlaced = bool("OnlyPlaced", true, "Only signs you just placed (off = also when you open an existing sign to edit it).");

    private int sincePlace = 1000;
    private Object handled;

    public AutoSign() { super("AutoSign", "Fills signs you place with your text.", Category.WORLD); }

    @Override
    public void onPacketSend(PacketEvent event) {
        // the client places its predicted block (and shrinks the stack) before this packet is built, so look at what
        // now stands where the click went rather than at the hand, which may already be empty
        if (event.packet instanceof ServerboundUseItemOnPacket use && mc.level != null) {
            var hit = use.hitResult();
            if (mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof SignBlock
                || mc.level.getBlockState(hit.getBlockPos().relative(hit.getDirection())).getBlock() instanceof SignBlock) sincePlace = 0;
        }
    }

    @Override
    public void onTick() {
        if (sincePlace < 1000) sincePlace++;
        if (!(mc.gui.screen() instanceof AbstractSignEditScreen screen) || screen == handled) return;
        if (onlyPlaced.get() && sincePlace > 40) return;
        handled = screen;
        String[] lines = ((SignEditAccessor) screen).prism$messages();
        String[] text = {line1.get(), line2.get(), line3.get(), line4.get()};
        for (int i = 0; i < lines.length && i < text.length; i++) lines[i] = text[i];
        sincePlace = 1000;
        mc.gui.setScreen(null); // removed() sends the update with these lines
    }
}
