package dev.prismglass.dev;

import static dev.prismglass.dev.ModTest.*;
import static dev.prismglass.dev.ModCases.clearArea;

import dev.prismglass.Prism;
import dev.prismglass.module.anarchy.PluginDupe;
import dev.prismglass.module.client.KeyActions;
import dev.prismglass.module.movement.NoSlow;
import dev.prismglass.module.render.FatigueAnim;
import dev.prismglass.module.render.Inspect;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/** ModTest cases for 1.6.4: FatigueAnim, keybind-only Inspect, PluginDupe, the old-Grim NoSlow modes. */
final class ModCases9 {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int slowSwing, fastSwing, before;
    private static BlockPos chest;

    private ModCases9() {}

    /** Ticks the local player stays swinging after a swing, counted from the case's ticks. */
    private static int countSwing(int startAt) {
        if (caseTicks == startAt) dev.prismglass.util.SwingUtil.punch();
        return caseTicks > startAt && mc.player.isSwinging() ? 1 : 0;
    }

    private static boolean offBefore, onTyping;

    /** Whether SDL text input is switched on right now (TextInputManager keeps it in a private flag). */
    private static boolean textInputOn() {
        try {
            var f = com.mojang.blaze3d.platform.TextInputManager.class.getDeclaredField("textInputEnabled");
            f.setAccessible(true);
            return f.getBoolean(mc.textInputManager());
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    static void register() {
        add("TextInput: Prism fields switch SDL text input on while typed into (26.3 typing fix)", 40,
            () -> mc.gui.setScreen(new dev.prismglass.gui.ClickGuiScreen()), () -> {
            if (!(mc.gui.screen() instanceof dev.prismglass.gui.ClickGuiScreen g)) return false;
            if (caseTicks == 3) offBefore = !textInputOn();
            if (caseTicks == 4) g.debugFocusSearch(true);
            if (caseTicks == 8) { onTyping = textInputOn(); g.debugFocusSearch(false); }
            if (caseTicks < 12) return false;
            boolean offAfter = !textInputOn();
            log("textinput: off before " + offBefore + ", on while typing " + onTyping + ", off after " + offAfter);
            return offBefore && onTyping && offAfter;
        }, () -> mc.gui.setScreen(null));

        add("FatigueAnim: slower swing, Inspect at the fatigue ratio, real fatigue counts", 120, () -> {
            cmd("effect clear @p");
            cmd("item replace entity @p weapon.mainhand with diamond_sword");
            enable(FatigueAnim.class).level.parse("3");
            slowSwing = fastSwing = 0;
        }, () -> {
            slowSwing += countSwing(5);
            if (caseTicks == 30) disable(FatigueAnim.class);
            fastSwing += countSwing(32);
            if (caseTicks == 30) {
                enable(FatigueAnim.class);
                log(f("fatigueanim: animScale(level 3) = %.3f", FatigueAnim.animScale(true)));
                disable(FatigueAnim.class);
            }
            if (caseTicks == 55) cmd("effect give @p mining_fatigue 30 1 true");
            if (caseTicks < 70) return false;
            float real = FatigueAnim.animScale(true);
            Inspect in = Prism.modules().get(Inspect.class);
            in.trigger();
            float secs = in.clipSeconds(), rate = in.rate();
            in.cancel();
            log(f("fatigueanim: swing %d ticks with level 3 vs %d without; real fatigue II scale %.3f; inspect rate %.3f clip %.2fs",
                slowSwing, fastSwing, real, rate, secs));
            return slowSwing >= fastSwing + 5 && Math.abs(real - 0.6f) < 1e-4 && Math.abs(rate - in.speed.getFloat() * 0.6f) < 1e-4;
        }, () -> { disable(FatigueAnim.class); cmd("effect clear @p"); cmd("clear @p"); });

        add("Inspect: keybind only (hidden, can't be switched off, survives Panic)", 20, () -> {
            cmd("item replace entity @p weapon.mainhand with diamond_sword");
        }, () -> {
            if (caseTicks < 4) return false;
            Inspect in = Prism.modules().get(Inspect.class);
            in.toggle();
            in.setEnabled(false);
            KeyActions.panic();
            boolean on = in.isEnabled();
            Prism.modules().get(KeyActions.class).actions().get(0).run().run(); // the Inspect key
            boolean playing = in.inspecting();
            in.cancel();
            log("inspect: internal=" + in.internal() + " enabled=" + on + " keyPlays=" + playing);
            return in.internal() && on && playing;
        }, () -> cmd("clear @p"));

        add("PluginDupe: store into a chest, dry-run kick; CloseRace takes it back", 80, () -> {
            clearArea();
            chest = new BlockPos(px + 2, py, pz);
            cmd(f("setblock %d %d %d chest", chest.getX(), chest.getY(), chest.getZ()));
            cmd("clear @p");
            cmd("give @p diamond 64");
            cmd("give @p emerald 30");
            PluginDupe d = Prism.modules().get(PluginDupe.class);
            d.dryRun = true;
            d.preset.parse("Custom");
            d.script.parse("waitgui; store; kick");
        }, () -> {
            PluginDupe d = Prism.modules().get(PluginDupe.class);
            if (caseTicks == 6) d.setEnabled(true);
            if (caseTicks == 8) ModCases8.open(chest);
            if (caseTicks == 25) {
                before = splayer().getInventory().countItem(Items.DIAMOND);
                log("plugindupe: trace " + d.trace + ", server diamonds after store " + before);
                mc.player.closeContainer();
            }
            if (caseTicks == 30) { d.preset.parse("CloseRace"); d.setEnabled(true); }
            if (caseTicks == 32) ModCases8.open(chest);
            if (caseTicks < 60) return false;
            int back = splayer().getInventory().countItem(Items.DIAMOND);
            d.command.parse("pv 2");
            String filled = String.join("; ", d.parse("cmd {cmd}; click {slot} 0 swap {times}"));
            log("plugindupe: closerace trace " + d.trace + ", diamonds back " + back + ", filled '" + filled + "'");
            return before == 0 && back == 64 && !d.isEnabled() && filled.equals("cmd pv 2; click 13 0 swap 8");
        }, () -> {
            PluginDupe d = Prism.modules().get(PluginDupe.class);
            d.setEnabled(false);
            d.dryRun = false;
            d.preset.reset(); d.script.reset(); d.command.reset();
            if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
            cmd("clear @p");
        });

        add("NoSlow GrimSwitch: off-hand golden apple still gets eaten through slot swaps", 90, () -> {
            cmd("clear @p");
            cmd("item replace entity @p weapon.offhand with golden_apple 4");
            enable(NoSlow.class).mode.parse("GrimSwitch");
            Prism.modules().get(NoSlow.class).tricks = 0;
        }, () -> {
            if (caseTicks == 4) mc.options.keyUse.setDown(true);
            if (caseTicks < 50) return false;
            mc.options.keyUse.setDown(false);
            int left = splayer().getOffhandItem().getCount();
            log("noslow grimswitch: tricks " + Prism.modules().get(NoSlow.class).tricks + ", apples left " + left);
            return Prism.modules().get(NoSlow.class).tricks > 10 && left < 4;
        }, () -> { mc.options.keyUse.setDown(false); disable(NoSlow.class); cmd("clear @p"); cmd("effect clear @p"); });

        add("NoSlow GrimOffhand: main-hand golden apple still gets eaten with off-hand use packets", 90, () -> {
            cmd("clear @p");
            cmd("item replace entity @p weapon.mainhand with golden_apple 4");
            enable(NoSlow.class).mode.parse("GrimOffhand");
            Prism.modules().get(NoSlow.class).tricks = 0;
        }, () -> {
            if (caseTicks == 4) mc.options.keyUse.setDown(true);
            if (caseTicks < 50) return false;
            mc.options.keyUse.setDown(false);
            int left = splayer().getMainHandItem().getCount();
            log("noslow grimoffhand: tricks " + Prism.modules().get(NoSlow.class).tricks + ", apples left " + left);
            return Prism.modules().get(NoSlow.class).tricks > 10 && left < 4;
        }, () -> {
            mc.options.keyUse.setDown(false);
            Prism.modules().get(NoSlow.class).mode.reset();
            disable(NoSlow.class);
            cmd("clear @p"); cmd("effect clear @p");
        });
    }
}
