package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.module.player.PacketMine;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.glfw.GLFW;

/**
 * Dev only (-Pminetest): chat tab completion through the real ChatScreen, PacketMine in both modes on obsidian with
 * the pickaxe in another slot (predicted vs real break ticks), and BetterTooltips lines. Flat survival world.
 */
public final class MineTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final String[] TYPED = {".tog", ".set crystala", ".set CrystalAura swa", ".set CrystalAura Swap S", ".fri", ".friend a", ".bind surround f1", ".nope x"};
    private static int ticks, t, step, phase, mineStart, expected;
    private static boolean requested;
    private static BlockPos target;

    private MineTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[minetest] {}", s); }

    private static void run(MinecraftServer s, String cmd) {
        s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static Object field(Object o, Class<?> type) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) if (type.isAssignableFrom(f.getType())) { f.setAccessible(true); return f.get(o); }
        throw new NoSuchFieldException(type.getSimpleName());
    }

    private static void tick() {
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (!requested && ticks == 80 && mc.level == null) {
            requested = true;
            String name = "prismmine-" + System.currentTimeMillis();
            LevelSettings info = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, info, new WorldOptions(1L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
            || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        t++;
        MinecraftServer s = mc.getSingleplayerServer();
        if (t == 30) {
            for (var m : Prism.modules().all())
                if (m.isEnabled() && !(m instanceof dev.prismglass.module.client.AntiCheat) && !(m instanceof dev.prismglass.module.client.Hud)) m.setEnabled(false);
            Prism.anticheat().preset.parse("Custom");
            Prism.anticheat().update();
            Prism.anticheat().preset.parse("Grim");
            Prism.anticheat().update();
            run(s, "gamerule advance_time false");
            run(s, "item replace entity @p hotbar.0 with stick");
            run(s, "item replace entity @p hotbar.2 with netherite_pickaxe[enchantments={efficiency:5}]");
            run(s, "item replace entity @p hotbar.4 with diamond_sword[damage=1200]");
            run(s, "item replace entity @p hotbar.5 with obsidian 37");
            run(s, "item replace entity @p inventory.0 with obsidian 64");
            run(s, "item replace entity @p hotbar.6 with cooked_beef 3");
            if (mc.screen != null) mc.setScreen(null);
        }
        if (t < 60) return;
        try {
            if (phase == 0) completion();
            else if (phase == 1) mining();
            else if (phase == 2) tooltips();
            else if (phase == 3) antiSurround();
        } catch (Exception e) {
            log("FAILED: " + e);
            Runtime.getRuntime().halt(0);
        }
    }

    /** Type each half command into a real ChatScreen (through EditBox.insertText, like typing), press Tab. */
    private static void completion() throws Exception {
        if (t % 10 != 0) return;
        if (step >= TYPED.length) { mc.setScreen(null); phase = 1; step = 0; t = 100; return; }
        if (!(mc.screen instanceof ChatScreen)) { mc.setScreen(new ChatScreen("", false)); return; }
        ChatScreen chat = (ChatScreen) mc.screen;
        EditBox input = (EditBox) field(chat, EditBox.class);
        CommandSuggestions sug = (CommandSuggestions) field(chat, CommandSuggestions.class);
        String typed = TYPED[step];
        input.setValue("");
        input.insertText(typed);
        boolean shown = sug.isVisible();
        sug.keyPressed(new KeyEvent(GLFW.GLFW_KEY_TAB, 0, 0));
        log(String.format("typed '%s' -> list %s, after Tab: '%s'", typed, shown ? "shown" : "NOT shown", input.getValue()));
        var c = Prism.commands().complete(typed);
        log("   options " + (c == null ? "null" : c.options().subList(0, Math.min(6, c.options().size())) + (c.options().size() > 6 ? " +" + (c.options().size() - 6) : ""))
            + " | usage: " + (c == null ? null : c.usage()));
        step++;
    }

    private static void mining() {
        MinecraftServer s = mc.getSingleplayerServer();
        PacketMine pm = Prism.modules().get(PacketMine.class);
        String[] modes = {"Instant", "Strict"};
        if (step >= modes.length) { phase = 3; t = 100; step = 0; return; }
        if (t == 101) {
            pm.mode.parse(modes[step]);
            pm.swap.parse("Silent");
            pm.setEnabled(true);
            mc.player.getInventory().setSelectedSlot(0);
            BlockPos feet = mc.player.blockPosition();
            target = feet.offset(2, 0, 0);
            run(s, String.format("setblock %d %d %d obsidian", target.getX(), target.getY(), target.getZ()));
            return;
        }
        if (t == 110) {
            expected = pm.ticksToBreak(target, 2);
            boolean took = pm.onClick(target, Direction.WEST);
            mineStart = t;
            log(String.format("[%s] clicked obsidian holding a stick (pick in slot 3): accepted=%s, vanilla needs %d ticks with the pick",
                modes[step], took, expected));
            return;
        }
        if (t > 110) {
            boolean gone = mc.level.getBlockState(target).isAir();
            boolean serverGone = s.overworld().getBlockState(target).isAir();
            if (gone && serverGone) {
                log(String.format("[%s] broken after %d ticks (vanilla %d); client slot still %d; obsidian in inventory: %d",
                    modes[step], t - mineStart, expected, mc.player.getInventory().getSelectedSlot() + 1,
                    mc.player.getInventory().countItem(net.minecraft.world.item.Items.OBSIDIAN)));
                pm.setEnabled(false);
                step++;
                t = 100;
            } else if (t - mineStart > expected + 120) {
                log(String.format("[%s] NOT broken after %d ticks (client air=%s, server air=%s)", modes[step], t - mineStart, gone, serverGone));
                pm.setEnabled(false);
                step++;
                t = 100;
            }
        }
    }

    /**
     * A NoAI zombie in an obsidian surround 4 blocks east stands in for an enemy (Mobs on). Broken mode: mine one
     * surround block -> a crystal must land in that slot. Re-enabled (memory wiped) with an old gap -> Broken must leave
     * it alone, Gaps must fill it.
     */
    private static BlockPos me, zPos;
    private static int brokeAt;

    private static void antiSurround() {
        MinecraftServer s = mc.getSingleplayerServer();
        var as = Prism.modules().get(dev.prismglass.module.combat.AntiSurround.class);
        if (t == 101) {
            me = mc.player.blockPosition();
            zPos = me.offset(4, 0, 0);
            int y = me.getY();
            run(s, "difficulty easy");
            run(s, "time set midnight");
            run(s, "kill @e[type=!player]");
            run(s, String.format("fill %d %d %d %d %d %d obsidian", me.getX() + 2, y - 1, me.getZ() - 2, me.getX() + 6, y - 1, me.getZ() + 2));
            for (var d : dev.prismglass.util.BlockUtil.HORIZONTALS) {
                BlockPos p = zPos.relative(d);
                run(s, String.format("setblock %d %d %d obsidian", p.getX(), p.getY(), p.getZ()));
            }
            run(s, "gamerule spawn_mobs false");
            run(s, String.format(java.util.Locale.ROOT, "summon zombie %.1f %d %.1f {NoAI:1b,PersistenceRequired:1b,Health:500f,attributes:[{id:\"minecraft:max_health\",base:500}],"
                + "equipment:{head:{id:\"iron_helmet\"}}}", zPos.getX() + 0.5, y, zPos.getZ() + 0.5));
            for (String slot : new String[]{"head", "chest", "legs", "feet"})
                run(s, "item replace entity @p armor." + slot + " with netherite_" + switch (slot) { case "head" -> "helmet"; case "chest" -> "chestplate"; case "legs" -> "leggings"; default -> "boots"; }
                    + "[enchantments={blast_protection:4}]");
            run(s, "item replace entity @p hotbar.7 with end_crystal 64");
            mc.player.getInventory().setSelectedSlot(0);
            return;
        }
        BlockPos front = zPos == null ? null : zPos.west(), side = zPos == null ? null : zPos.south();
        if (t == 130) {
            as.mobs.set(true);
            as.mode.parse("Broken");
            as.swap.parse("Normal");
            as.setEnabled(true);
            log(String.format("[antisurround] zombie in hole at %s; predicted self damage from the front slot: %.1f (max %.1f)", zPos,
                dev.prismglass.util.DamageUtil.crystalDamage(mc.player, dev.prismglass.util.CombatUtil.crystalPos(front.below())), as.maxSelf.get()));
        }
        if (t == 140) {
            run(s, String.format("setblock %d %d %d air", front.getX(), front.getY(), front.getZ()));
            brokeAt = t;
        }
        if (t > 140 && t <= 180 && brokeAt > 0 && crystalAt(front)) {
            log(String.format("[antisurround] Broken: crystal in the mined slot %d ticks after it broke; holding slot %d", t - brokeAt,
                mc.player.getInventory().getSelectedSlot() + 1));
            brokeAt = 0;
        }
        if (t == 181) {
            if (brokeAt > 0) log("[antisurround] Broken: NO crystal in the mined slot after 40 ticks");
            as.setEnabled(false);
            run(s, "kill @e[type=end_crystal]");
            run(s, String.format("setblock %d %d %d obsidian", front.getX(), front.getY(), front.getZ()));
            run(s, String.format("setblock %d %d %d air", side.getX(), side.getY(), side.getZ()));
        }
        if (t == 190) as.setEnabled(true);
        if (t == 220) {
            log("[antisurround] Broken with an old (never seen) gap: " + (crystalAt(side) ? "placed (WRONG)" : "left alone (ok)"));
            as.mode.parse("Gaps");
            brokeAt = t;
        }
        if (t > 220 && t <= 260 && brokeAt > 0 && crystalAt(side)) {
            log(String.format("[antisurround] Gaps: crystal in the open slot after %d ticks", t - brokeAt));
            brokeAt = 0;
        }
        if (t == 261) {
            if (brokeAt > 0) log("[antisurround] Gaps: NO crystal in the open slot after 40 ticks");
            as.setEnabled(false);
            run(s, "kill @e[type=end_crystal]");
            phase = 2;
            t = 100;
        }
    }

    private static boolean crystalAt(BlockPos slot) {
        return !mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, new net.minecraft.world.phys.AABB(slot)).isEmpty();
    }

    private static void tooltips() {
        if (t != 110) return;
        var bt = Prism.modules().get(dev.prismglass.module.render.BetterTooltips.class);
        bt.setEnabled(true);
        for (int slot : new int[]{2, 4, 5, 6}) {
            var stack = mc.player.getInventory().getItem(slot);
            var lines = stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
            StringBuilder sb = new StringBuilder();
            for (var l : lines) sb.append(" | ").append(l.getString());
            log("tooltip " + stack.getHoverName().getString() + sb);
        }
        log("done");
        Runtime.getRuntime().halt(0);
    }
}
