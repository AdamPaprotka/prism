package dev.prismglass.module.player;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Middle click: on a player toggles friend; anywhere else uses the Air item from your hotbar with a silent swap and
 * swaps back (pearl, rocket, XP bottle, wind charge, golden apple). While gliding it fires a rocket instead when
 * ElytraRocket is on. XP bottles and gapples keep going while the button is held.
 */
public class MiddleClick extends Module {
    public final BoolSetting friends = bool("Friends", true, "Middle click players to friend/unfriend.");
    public final ModeSetting air = mode("Air", "Pearl", "What middle clicking anywhere else uses.", "Pearl", "Rocket", "XP", "WindCharge", "Gapple", "None");
    public final BoolSetting elytraRocket = bool("ElytraRocket", true, "While gliding, fire a rocket instead.");
    public final NumberSetting xpDelay = num("XPDelay", 1, 0, 10, 1, "XP: ticks between bottles while held.").visibleWhen(() -> air.is("XP"));
    /** Old configs: Pearl off meant "no air action". */
    public final BoolSetting pearl = bool("Pearl", true, "Old switch: off = Air does nothing.").visibleWhen(() -> false);

    private boolean wasDown;
    private int held, eating = -1;

    public MiddleClick() { super("MiddleClick", "Middle click: friend players, or pearl / rocket / XP / wind charge / gapple.", Category.PLAYER); }

    @Override
    public void onDisable() { stopEating(); }

    @Override
    public void onTick() {
        boolean down = mc.screen == null && GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (down && !wasDown) { held = 0; click(); }
        else if (down) { held++; hold(); }
        else stopEating();
        wasDown = down;
    }

    private Item airItem() {
        if (elytraRocket.get() && mc.player.isFallFlying()) return Items.FIREWORK_ROCKET;
        return switch (air.get()) {
            case "Pearl" -> Items.ENDER_PEARL;
            case "Rocket" -> Items.FIREWORK_ROCKET;
            case "XP" -> Items.EXPERIENCE_BOTTLE;
            case "WindCharge" -> Items.WIND_CHARGE;
            case "Gapple" -> InvUtil.findHotbar(Items.ENCHANTED_GOLDEN_APPLE) != -1 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
            default -> null;
        };
    }

    private void click() {
        if (friends.get() && mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof Player p) {
            String name = p.getGameProfile().name();
            if (Prism.friends().isFriend(name)) { Prism.friends().remove(name); ChatUtil.info("Unfriended " + name); }
            else { Prism.friends().add(name); ChatUtil.good("Friended " + name); }
            return;
        }
        if (!pearl.get()) return;
        Item item = airItem();
        if (item == null) return;
        if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) { startEating(item); return; }
        use(item);
    }

    private void hold() {
        Item item = airItem();
        if (item == Items.EXPERIENCE_BOTTLE && held % (xpDelay.getInt() + 1) == 0) use(item);
        if (eating != -1 && !mc.player.isUsingItem() && held > 40) stopEating();
    }

    /** Throw / fire once from the hotbar (offhand first) and put the slot back. */
    private void use(Item item) {
        if (mc.player.getOffhandItem().is(item)) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            mc.player.swing(InteractionHand.OFF_HAND);
            return;
        }
        int slot = InvUtil.findHotbar(item);
        if (slot == -1 || !Prism.guard().canSwitchSlot()) return;
        int prev = mc.player.getInventory().getSelectedSlot();
        InvUtil.swap(slot, false);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        InvUtil.restore(prev);
    }

    /** Gapples take 32 ticks: hold the slot and the use key until the button is let go. */
    private void startEating(Item item) {
        int slot = InvUtil.findHotbar(item);
        if (slot == -1 || !Prism.guard().canSwitchSlot()) return;
        eating = mc.player.getInventory().getSelectedSlot();
        InvUtil.swap(slot, false);
        mc.options.keyUse.setDown(true);
    }

    private void stopEating() {
        if (eating == -1) return;
        mc.options.keyUse.setDown(false);
        InvUtil.restore(eating);
        eating = -1;
    }
}
