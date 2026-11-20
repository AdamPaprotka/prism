package dev.prismglass.module.anarchy;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plugin dupes. Almost every server-plugin dupe is a race between two things the plugin and the server handle
 * separately: a vault / backpack / shulker-from-hand plugin that saves its storage when the screen closes while the
 * player file is saved on quit, a GUI that hands out items per click before it checks the listing is gone, a trade
 * screen that pays out on close. This module replays such a race exactly: a short script of steps, all sent in one tick
 * (in order) until a wait. Presets cover the usual shapes; Custom runs your own Script. Started with
 * {@code .dupe <preset> [command]} or by turning it on.
 *
 * <p>Steps, separated by ';' ({cmd} {slot} {times} are filled in from the settings):
 * <pre>
 *   cmd &lt;text&gt;        run a command (no slash)              wait &lt;ticks&gt;      continue later
 *   waitgui [ticks]     wait for a container screen            store / take     shift-click everything in / out
 *   click &lt;slot&gt; [button] [pickup|quick|swap|throw|clone|all] [times]
 *   close               tell the server the screen closed (ours stays open, later clicks race the close)
 *   closeall            close it normally                      drop             throw the held stack
 *   use                 use the held item                      mountinv         open the inventory of what you ride
 *   dismount            get off                                kick             make the server kick you now
 *   disconnect          drop the connection from our side
 * </pre>
 * {@code kick} sends a movement packet with NaN coordinates: the server processes it after everything sent before it in
 * the same tick and kicks ("invalid player movement"), saving the player file right there, before anything a plugin
 * schedules for later. Vanilla and Paper never duplicate with any of these on their own; they only work where a
 * plugin has the race (see PluginScanner's advice for which preset fits a detected plugin).
 */
public class PluginDupe extends Module {
    /** Preset scripts. */
    public static final Map<String, String> PRESETS = new LinkedHashMap<>();
    /** One-line descriptions of the presets. */
    public static final Map<String, String> ABOUT = new LinkedHashMap<>();

    private static void preset(String name, String script, String about) { PRESETS.put(name, script); ABOUT.put(name, about); }

    static {
        preset("VaultKick", "cmd {cmd}; waitgui; store; kick", "Vault/backpack plugins: put everything in, kicked before the vault save (keeps inventory + vault).");
        preset("VaultTake", "cmd {cmd}; waitgui; take; kick", "Vault/backpack plugins that save on close: take it all out, kicked before the vault is saved empty.");
        preset("EChestKick", "cmd {cmd}; waitgui; take; kick", "/ec plugins (Essentials, CMI...): take out, kicked in the same tick.");
        preset("ShulkerKick", "use; waitgui; take; kick", "Open-shulker-from-hand plugins: take the contents out of the held box, kicked before the box is rewritten.");
        preset("MountKick", "mountinv; waitgui; store; dismount; kick", "Donkey/llama/mule chest: store, dismount and get kicked in one tick (ride a chested animal).");
        preset("CloseRace", "waitgui; take; close; take", "Any plugin GUI: take, tell the server it closed, take again (GUIs that give items on close or per click).");
        preset("SpamClick", "waitgui; click {slot} 0 pickup {times}", "Auction / shop / trade GUIs: click one slot many times in one tick (cancel-listing, buy and claim races).");
        preset("SwapRace", "waitgui; click {slot} 0 swap {times}; close", "GUIs that check the cursor but not hotbar swaps: number-key swap the slot out, then close.");
        preset("DropKick", "drop; kick", "Old drop-then-disconnect dupe: throw the held stack and get kicked the same tick.");
        preset("Custom", "", "Your own Script.");
    }

    public final ModeSetting preset = mode("Preset", "VaultKick", "Which race to run (see .dupe list).", PRESETS.keySet().toArray(new String[0]));
    public final TextSetting command = text("Command", "pv 1", "{cmd}: the command that opens the storage (pv 1, vault, backpack, ec...).");
    public final NumberSetting slot = num("Slot", 13, 0, 89, 1, "{slot}: container slot to click (0 = top-left of the GUI).");
    public final NumberSetting times = num("Times", 8, 1, 64, 1, "{times}: clicks per click step.");
    public final TextSetting script = text("Script", "waitgui; take; close", "Custom: steps separated by ; (see the module description).").visibleWhen(() -> preset.is("Custom"));
    public final BoolSetting onlyHeld = bool("OnlyHeld", false, "store: only stacks of the item in your hand (keep the rest out of the race).");
    public final NumberSetting repeat = num("Repeat", 1, 1, 100, 1, "Runs (scripts without kick/disconnect only).");
    public final NumberSetting repeatDelay = num("RepeatDelay", 10, 1, 200, 1, "Ticks between runs.");

    /** Dev: kick/disconnect are logged instead of done. */
    public boolean dryRun;
    /** Dev: every step run, as "tick:step". */
    public final List<String> trace = new ArrayList<>();

    private List<String> steps = List.of();
    private int index, wait, guiWait, runs, tick;
    private boolean ended;

    public PluginDupe() { super("PluginDupe", "Replays plugin dupe races (vaults, backpacks, shulkers, mounts, GUIs) in one tick, with a kick where it helps.", Category.ANARCHY); }

    /** {@code .dupe <preset> [command]}. */
    public void start(String presetName, String cmd) {
        for (String p : PRESETS.keySet()) if (p.equalsIgnoreCase(presetName)) preset.parse(p);
        if (cmd != null && !cmd.isBlank()) command.parse(cmd.replaceFirst("^/", ""));
        if (isEnabled()) onEnable(); else setEnabled(true);
    }

    @Override
    public void onEnable() {
        steps = parse(preset.is("Custom") ? script.get() : PRESETS.get(preset.get()));
        if (steps.isEmpty()) { ChatUtil.error("PluginDupe: empty script"); toggle(); return; }
        index = 0; wait = 0; guiWait = -1; runs = 0; tick = 0; ended = false;
        trace.clear();
        ChatUtil.info("PluginDupe: §f" + preset.get() + "§7 - " + String.join("; ", steps));
    }

    @Override public String getInfo() { return preset.get(); }

    /** The script with {cmd} {slot} {times} filled in, one step per entry. */
    public List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String filled = text.replace("{cmd}", command.get().replaceFirst("^/", ""))
            .replace("{slot}", String.valueOf(slot.getInt())).replace("{times}", String.valueOf(times.getInt()));
        for (String s : filled.split(";")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    @Override
    public void onTick() {
        tick++;
        if (wait > 0) { wait--; return; }
        while (index < steps.size() && !ended) {
            String step = steps.get(index);
            String[] a = step.split("\\s+");
            String op = a[0].toLowerCase(Locale.ROOT);
            if (op.equals("waitgui")) {
                if (guiWait < 0) guiWait = a.length > 1 ? parseInt(a[1], 40) : 40;
                if (!guiOpen()) {
                    if (--guiWait < 0) { fail("no screen opened"); return; }
                    return;
                }
                guiWait = -1;
                trace.add(tick + ":" + step);
                index++;
                continue;
            }
            trace.add(tick + ":" + step);
            index++;
            if (op.equals("wait")) { wait = Math.max(0, parseInt(a.length > 1 ? a[1] : "1", 1) - 1); return; }
            if (!run(op, a)) return;
        }
        if (ended) return;
        // finished
        boolean final_ = steps.stream().anyMatch(s -> s.startsWith("kick") || s.startsWith("disconnect"));
        if (++runs < repeat.getInt() && !final_) {
            index = 0;
            wait = repeatDelay.getInt();
            return;
        }
        ChatUtil.good("PluginDupe: done (" + runs + " run" + (runs == 1 ? "" : "s") + ")");
        ended = true;
        toggle();
    }

    private boolean guiOpen() {
        return mc.player.containerMenu != null && mc.player.containerMenu != mc.player.inventoryMenu;
    }

    private void fail(String why) {
        ChatUtil.error("PluginDupe: " + why + " at step " + (index + 1) + " (" + steps.get(Math.min(index, steps.size() - 1)) + ")");
        ended = true;
        toggle();
    }

    /** Runs one step now. False = stop this tick (the step failed and the module turned off). */
    private boolean run(String op, String[] a) {
        var conn = mc.getConnection();
        AbstractContainerMenu menu = mc.player.containerMenu;
        switch (op) {
            case "cmd" -> {
                String text = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).replaceFirst("^/", "");
                conn.sendCommand(text);
            }
            case "store", "take" -> {
                if (!guiOpen()) { fail("no screen open"); return false; }
                int container = menu.slots.size() - 36; // the last 36 slots are your inventory
                int from = op.equals("store") ? container : 0, to = op.equals("store") ? menu.slots.size() : container;
                ItemStack held = mc.player.getMainHandItem();
                for (int i = from; i < to; i++) {
                    ItemStack s = menu.getSlot(i).getItem();
                    if (s.isEmpty()) continue;
                    if (op.equals("store") && onlyHeld.get() && !ItemStack.isSameItemSameComponents(s, held)) continue;
                    mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                }
            }
            case "click" -> {
                if (!guiOpen()) { fail("no screen open"); return false; }
                int s = parseInt(a.length > 1 ? a[1] : "0", 0), button = parseInt(a.length > 2 ? a[2] : "0", 0);
                ContainerInput in = a.length > 3 ? input(a[3]) : ContainerInput.PICKUP;
                int n = Math.max(1, Math.min(64, parseInt(a.length > 4 ? a[4] : "1", 1)));
                if (s < 0 || s >= menu.slots.size()) { fail("slot " + s + " is outside this screen"); return false; }
                for (int i = 0; i < n; i++) mc.gameMode.handleContainerInput(menu.containerId, s, button, in, mc.player);
            }
            case "close" -> conn.send(new ServerboundContainerClosePacket(menu.containerId));
            case "closeall" -> mc.player.closeContainer();
            case "drop" -> {
                mc.gameMode.dropItem(mc.player, true);
            }
            case "use" -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            case "mountinv" -> {
                if (mc.player.getVehicle() == null) { fail("not riding anything"); return false; }
                conn.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.OPEN_INVENTORY));
            }
            case "dismount" -> conn.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, true, false)));
            case "kick" -> {
                ended = true;
                if (dryRun) { ChatUtil.info("PluginDupe: kick (dry run)"); toggle(); return false; }
                conn.send(new ServerboundMovePlayerPacket.Pos(Double.NaN, Double.NaN, Double.NaN, false, false));
                toggle();
                return false;
            }
            case "disconnect" -> {
                ended = true;
                toggle();
                if (!dryRun) conn.getConnection().disconnect(Component.literal("[Prism PluginDupe]"));
                return false;
            }
            default -> { fail("unknown step '" + op + "'"); return false; }
        }
        return true;
    }

    private static ContainerInput input(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "quick", "shift" -> ContainerInput.QUICK_MOVE;
            case "swap" -> ContainerInput.SWAP;
            case "throw" -> ContainerInput.THROW;
            case "clone" -> ContainerInput.CLONE;
            case "all", "pickup_all" -> ContainerInput.PICKUP_ALL;
            default -> ContainerInput.PICKUP;
        };
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
