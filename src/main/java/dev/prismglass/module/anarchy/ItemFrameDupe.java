package dev.prismglass.module.anarchy;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/**
 * The anarchy-server item frame dupe: put the held item into a frame, punch it back out, repeat. Plain
 * vanilla/Paper never duplicates here; servers that run a frame-dupe plugin (a chance for the punched
 * item to drop twice) do. Stand next to the frames so the drops land in your inventory.
 * Uses the closest frame; one frame action per tick.
 */
public class ItemFrameDupe extends Module {
    public final NumberSetting range = num("Range", 3, 1, 3, 0.1, "Frame reach (the server allows 3).");
    public final NumberSetting delay = num("Delay", 2, 0, 20, 1, "Ticks between frame actions.");
    public final NumberSetting rotations = num("Rotations", 0, 0, 7, 1, "Turn the item this many times before punching it out (some plugins only roll on rotated frames).");
    public final BoolSetting refill = bool("Refill", true, "When your hand runs out, take the same item from your inventory.");

    private Item item;
    private int wait, turns, cycles;

    public ItemFrameDupe() { super("ItemFrameDupe", "Places and punches items out of item frames (frame-dupe servers).", Category.ANARCHY); }

    @Override
    public void onEnable() {
        cycles = 0; wait = 0; turns = 0;
        ItemStack hand = mc.player.getMainHandItem();
        item = hand.isEmpty() ? null : hand.getItem();
        if (item == null) { ChatUtil.error("ItemFrameDupe: hold the item to dupe"); toggle(); }
    }

    @Override public String getInfo() { return String.valueOf(cycles); }

    @Override
    public void onTick() {
        if (wait > 0) { wait--; return; }
        if (!mc.player.getMainHandItem().is(item) && !refillHand()) return;

        ItemFrame frame = null;
        double best = range.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemFrame f) || !f.isAlive()) continue;
            double d = mc.player.getEyePosition().distanceTo(f.getBoundingBox().getCenter());
            if (d <= best) { best = d; frame = f; }
        }
        if (frame == null || !Prism.guard().canInteractEntity(frame.getId())) return;
        ItemStack inFrame = frame.getItem();
        if (inFrame.isEmpty()) {
            CombatUtil.face(frame, 40);
            mc.gameMode.interact(mc.player, frame, new EntityHitResult(frame), InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            turns = 0;
        } else if (inFrame.is(item) && turns < rotations.getInt()) {
            CombatUtil.face(frame, 40);
            mc.gameMode.interact(mc.player, frame, new EntityHitResult(frame), InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            turns++;
        } else {
            CombatUtil.face(frame, 40);
            if (!CombatUtil.attack(frame, false)) return;
            if (inFrame.is(item)) cycles++;
        }
        wait = delay.getInt(); // one frame action per tick
    }

    private boolean refillHand() {
        if (!refill.get() || !mc.player.getMainHandItem().isEmpty()) return false;
        int hot = InvUtil.findHotbar(item);
        if (hot != -1) return Prism.guard().canSwitchSlot() && InvUtil.swap(hot, false);
        int inv = InvUtil.findInventory(item);
        if (inv == -1 || !InvUtil.canClick()) return false;
        InvUtil.swapWithHotbar(inv, mc.player.getInventory().getSelectedSlot());
        return false; // hand fills once the server confirms
    }
}
