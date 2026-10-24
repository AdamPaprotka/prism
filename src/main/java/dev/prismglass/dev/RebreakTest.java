package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.player.PacketMine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev only (-Prebreaktest): PacketMine instant rebreak on the integrated (vanilla) server. Survival, Efficiency V
 * diamond pickaxe in hotbar slot 2 while holding slot 1 (silent swap). Mines obsidian A normally (~46 ticks), puts it
 * back and times the instant rebreak; then mines B, sends a START for a third block (the server forgets B) and checks
 * that B's rebreak falls back to a full re-mine. Logs "[rebreaktest]", exits.
 */
public final class RebreakTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, t, passed, failed, mark, phase;
    private static boolean requested;
    private static final BlockPos A = new BlockPos(2, -60, 0), B = new BlockPos(2, -60, 2), C = new BlockPos(-2, -60, 0);

    private RebreakTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[rebreaktest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static boolean air(BlockPos p) { return mc.level.getBlockState(p).isAir(); }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismrebreak-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        PacketMine pm = Prism.modules().get(PacketMine.class);

        if (t == 20) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            Prism.anticheat().preset.parse("Custom");
            Prism.anticheat().update();
            run(s, "gamerule advance_time false");
            run(s, "tp @p 0.5 -60 0.5 -90 30");
            run(s, "clear @p");
            run(s, "item replace entity @p hotbar.1 with diamond_pickaxe[enchantments={efficiency:5}]");
            run(s, "setblock " + A.getX() + " " + A.getY() + " " + A.getZ() + " obsidian");
            run(s, "setblock " + B.getX() + " " + B.getY() + " " + B.getZ() + " obsidian");
            run(s, "setblock " + C.getX() + " " + C.getY() + " " + C.getZ() + " obsidian");
            pm.mode.parse("Instant");
            pm.swap.parse("Silent");
            pm.rebreak.parse("Instant");
            pm.setEnabled(true);
        }
        if (t == 30) {
            mc.player.getInventory().setSelectedSlot(0);
            pm.onClick(A, Direction.WEST);
            mark = t;
            phase = 1;
        }
        if (phase == 1 && air(A)) {
            log("A mined normally in " + (t - mark) + " ticks");
            phase = 2;
            mark = t;
        }
        if (phase == 2 && t == mark + 10) {
            run(s, "setblock " + A.getX() + " " + A.getY() + " " + A.getZ() + " obsidian");
            mark = t;
            phase = 3;
        }
        if (phase == 3 && t > mark + 1 && air(A)) {
            int took = t - mark;
            check("instant rebreak of A took " + took + " ticks (normal mine ~46)", took <= 8);
            check("counted as instant rebreak (" + pm.instantRebreaks + ")", pm.instantRebreaks >= 1);
            check("held slot stayed 1 (silent swap)", mc.player.getInventory().getSelectedSlot() == 0);
            phase = 4;
            mark = t;
        }
        if (phase == 3 && t > mark + 60) { check("instant rebreak of A happened at all", false); phase = 99; }

        if (phase == 4 && t == mark + 10) {
            pm.onClick(B, Direction.WEST);
            mark = t;
            phase = 5;
        }
        if (phase == 5 && air(B)) {
            log("B mined normally in " + (t - mark) + " ticks");
            // a START on another block: the server now remembers C, not B
            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, C, Direction.WEST));
            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, C, Direction.WEST));
            phase = 6;
            mark = t;
        }
        if (phase == 6 && t == mark + 5) {
            run(s, "setblock " + B.getX() + " " + B.getY() + " " + B.getZ() + " obsidian");
            mark = t;
            phase = 7;
        }
        if (phase == 7 && t > mark + 1 && air(B)) {
            int took = t - mark;
            check("after a START elsewhere B is re-mined from scratch (" + took + " ticks, > 30)", took > 30);
            check("obsidian C untouched", mc.level.getBlockState(C).is(Blocks.OBSIDIAN));
            phase = 8;
            mark = t;
        }
        if (phase == 7 && t > mark + 200) { check("B fallback rebreak happened at all", false); phase = 99; }
        if ((phase == 8 && t == mark + 5) || phase == 99) {
            log("RESULT passed=" + passed + " failed=" + failed);
            phase = 100;
            mc.stop();
        }
    }
}
