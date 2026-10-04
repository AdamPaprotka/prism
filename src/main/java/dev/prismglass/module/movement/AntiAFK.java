package dev.prismglass.module.movement;


import dev.prismglass.Prism;
import dev.prismglass.event.MoveEvent;
import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;

public class AntiAFK extends Module {
    public final NumberSetting interval = num("Interval", 30, 5, 300, 5, "Seconds between actions.");
    public final BoolSetting jump = bool("Jump", true, "Jump.");
    public final BoolSetting swing = bool("Swing", true, "Swing your arm.");
    public final BoolSetting spin = bool("Spin", false, "Turn a little.");
    private final Timer timer = new Timer();

    public AntiAFK() { super("AntiAFK", "Avoid AFK kicks.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (!timer.tick(interval.get() * 1000)) return;
        if (jump.get() && mc.player.onGround()) mc.player.jumpFromGround();
        if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        if (spin.get()) mc.player.setYRot(mc.player.getYRot() + 15f);
    }
}
