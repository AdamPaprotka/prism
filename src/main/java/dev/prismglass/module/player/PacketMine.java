package dev.prismglass.module.player;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Click a block once and it gets mined in the background while you keep fighting, looking around or holding
 * anything. Vanilla's server works out break progress as (speed of the item held right now) x (ticks since START),
 * so the tool only has to be in hand at the very end: PacketMine swaps your best tool in for that moment.
 * <ul>
 *   <li>Instant: START and STOP go out together. The server remembers the half-done block ("delayed destroy")
 *       and breaks it on its own as soon as the held tool makes progress reach 1, so nothing more has to be sent.
 *       Vanilla, Paper and most anarchy servers.</li>
 *   <li>Strict: START now, STOP once the block is really done, with the tool swapped in a tick before the STOP so
 *       anticheats that track the held item tick by tick (Grim FastBreak, NCP) see it in hand.</li>
 * </ul>
 * Rebreak, when a mined block gets placed again:
 * <ul>
 *   <li>Instant: the server never forgets the position and start time of your last START (it only resets on the next
 *       START), and a STOP for that position counts every tick since then. So the block is broken with a lone STOP
 *       (best tool swapped in silently) the moment it reappears, as long as the time since START covers 70% of the
 *       break time with that tool. Any START elsewhere (you mined something else) resets it: then it's a normal
 *       rebreak.</li>
 *   <li>Normal: mine it again from scratch.</li>
 * </ul>
 */
public class PacketMine extends Module {
    public final ModeSetting mode = mode("Mode", "Instant", "Instant lets the server finish the block itself; Strict sends the stop packet at the end (Grim/NCP).", "Instant", "Strict");
    public final ModeSetting swap = mode("Swap", "Silent", "How the best tool gets into your hand at the end. Silent: only the server sees it. Normal: you see it too. Off: mine with whatever you hold.", "Silent", "Normal", "Off");
    public final NumberSetting speed = num("Speed", 1, 0.7, 1, 0.05, "Finish at this part of the vanilla break time. 1 is safe everywhere; vanilla servers accept down to 0.7.");
    public final NumberSetting range = num("Range", 5.5, 3, 6, 0.5, "Give up on blocks farther than this.");
    public final NumberSetting queueSize = num("Queue", 3, 1, 8, 1, "Click more blocks to queue them; they're mined one after another.");
    public final ModeSetting rebreak = mode("Rebreak", "Instant", "When a mined block gets placed again (an enemy fixing their surround): Instant breaks it again at once with a lone stop packet, Normal mines it again from scratch.", "Instant", "Normal", "Off");
    public final ColorSetting startColor = color("StartColor", 0xFFFF5C7A, "Box colour when the block was just started.");
    public final ColorSetting doneColor = color("DoneColor", 0xFF5CFF9D, "Box colour when it's about to break.");

    /** One block being mined. {@code ticks} counts server ticks since START (we tick in step with the server). */
    private static final class Job {
        final BlockPos pos;
        Direction face;
        int ticks;
        boolean started, stopSent;
        int swapSlot = -1, swapHeld;
        Job(BlockPos pos, Direction face) { this.pos = pos; this.face = face; }
    }

    private final Deque<Job> queue = new ArrayDeque<>();
    private Job current;
    private BlockPos rebreakPos;
    private Direction rebreakFace;
    private int silentSlot = -1;
    /** What the server remembers: the position of our last START and the tick it was sent (any START, ours or vanilla's). */
    private BlockPos serverDestroyPos;
    private int serverStartTick, clock;
    /** Instant rebreak in flight: the tick its STOP went out (-1 = none), and how many tries it got. */
    private int rebreakSentAt = -1, rebreakTries;
    /** The tool was swapped in for an instant rebreak whose STOP hasn't gone out yet: keep it in hand. */
    private boolean rebreakSwapped;
    /** Instant rebreaks done (dev test). */
    public int instantRebreaks;

    public PacketMine() { super("PacketMine", "Click once to mine blocks in the background; swaps the best tool in at the end.", Category.PLAYER); }

    @Override
    public void onDisable() {
        if (current != null && current.started && mc.player != null)
            mc.player.connection.send(new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, current.pos, current.face));
        releaseSilent();
        queue.clear();
        current = null;
        rebreakPos = null;
        rebreakSentAt = -1;
    }

    @Override
    public void onPacketSend(dev.prismglass.event.PacketEvent e) {
        if (e.packet instanceof ServerboundPlayerActionPacket p && p.getAction() == Action.START_DESTROY_BLOCK) {
            serverDestroyPos = p.getPos().immutable();
            serverStartTick = clock;
        }
    }

    @Override public void onWorldJoin() { serverDestroyPos = null; rebreakPos = null; rebreakSentAt = -1; }

    /** Called from {@code MultiPlayerGameMode.startDestroyBlock}; returning true swallows vanilla's own mining. */
    public boolean onClick(BlockPos pos, Direction face) {
        if (mc.player == null || mc.player.isCreative() || BlockUtil.isUnbreakable(pos)) return false;
        if (current != null && current.pos.equals(pos)) return true;
        for (Job j : queue) if (j.pos.equals(pos)) return true;
        if (current == null) current = new Job(pos.immutable(), face);
        else if (queue.size() + 1 < queueSize.getInt()) queue.add(new Job(pos.immutable(), face));
        else return true; // queue full: ignore the click instead of letting vanilla mine
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    @Override
    public void onTick() {
        if (nullCheck()) return;
        clock++;
        if (silentSlot != -1 && current == null && rebreakSentAt == -1 && !rebreakSwapped) releaseSilent();

        if (current == null) {
            current = queue.poll();
            boolean back = current == null && !rebreak.is("Off") && rebreakPos != null && !BlockUtil.isReplaceable(rebreakPos)
                && !BlockUtil.isUnbreakable(rebreakPos) && inRange(rebreakPos);
            if (back && rebreak.is("Instant") && rebreakPos.equals(serverDestroyPos) && rebreakTries < 3) {
                instantRebreak();
                return;
            }
            if (back) { current = new Job(rebreakPos, rebreakFace); rebreakSentAt = -1; rebreakTries = 0; rebreakSwapped = false; }
            else if (rebreakSwapped && rebreakSentAt == -1) rebreakSwapped = false; // target gone before the STOP: drop the swap
            if (current == null) {
                if (rebreakSentAt != -1 && rebreakPos != null && BlockUtil.isReplaceable(rebreakPos)) { // it broke
                    instantRebreaks++;
                    rebreakSentAt = -1;
                    rebreakTries = 0;
                    releaseSilent();
                }
                return;
            }
        }
        Job j = current;

        if (BlockUtil.isReplaceable(j.pos)) { // gone: broken by us or by someone else
            if (j.started) { rebreakPos = j.pos; rebreakFace = j.face; }
            finish();
            return;
        }
        if (!inRange(j.pos)) {
            if (j.started) mc.player.connection.send(new ServerboundPlayerActionPacket(Action.ABORT_DESTROY_BLOCK, j.pos, j.face));
            if (j.pos.equals(rebreakPos)) rebreakPos = null;
            finish();
            return;
        }

        if (!j.started) {
            Direction face = BlockUtil.breakFace(j.pos);
            if (face == null || !BlockUtil.readyToDig(j.pos, 70) || !Prism.guard().canPlace()) return;
            j.face = face;
            mc.player.connection.send(new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, j.pos, face));
            if (mode.is("Instant")) {
                mc.player.connection.send(new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, j.pos, face));
                j.stopSent = true;
            }
            mc.player.swing(InteractionHand.MAIN_HAND);
            j.started = true;
            j.ticks = 0;
            return;
        }

        j.ticks++;
        int tool = bestTool(BlockUtil.state(j.pos));
        int slot = tool == -1 || swap.is("Off") ? mc.player.getInventory().getSelectedSlot() : tool;
        int needed = ticksToBreak(j.pos, slot);
        if (needed == Integer.MAX_VALUE) return;
        // the server breaks at progress x (ticks + 1) >= 1 for a delayed destroy, >= 0.7 for a stop packet
        boolean nearlyDone = j.ticks + 1 >= Math.ceil(needed * speed.get()) - 1;

        if (nearlyDone && slot != mc.player.getInventory().getSelectedSlot() && j.swapSlot == -1) {
            if (!Prism.guard().canSwitchSlot()) return;
            if (swap.is("Silent")) { InvUtil.silentSwap(slot); silentSlot = slot; }
            else InvUtil.swap(slot, true);
            j.swapSlot = slot;
            return; // Strict: the stop packet goes out next tick, after the anticheat has seen the tool
        }
        if (j.swapSlot != -1) j.swapHeld++;

        if (mode.is("Strict") && nearlyDone && !j.stopSent && j.ticks + 1 >= Math.ceil(needed * speed.get())) {
            Direction face = BlockUtil.isFaceVisible(j.pos, j.face, mc.player.getEyePosition()) ? j.face : BlockUtil.breakFace(j.pos);
            if (face == null || !BlockUtil.readyToDig(j.pos, 70) || !Prism.guard().canPlace()) return;
            mc.player.connection.send(new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, j.pos, face));
            mc.player.swing(InteractionHand.MAIN_HAND);
            j.stopSent = true;
        }

        // server never broke it (lag, a different tool, an anticheat cancel): start over after a grace period
        if (j.ticks > needed + 20 + j.swapHeld) {
            releaseSilent();
            if (!swap.is("Silent")) InvUtil.swapBack();
            j.started = false;
            j.stopSent = false;
            j.swapSlot = -1;
            j.swapHeld = 0;
        }
    }

    /**
     * The block we last mined is back: a lone STOP breaks it if (progress per tick with the best tool) x (ticks since
     * the remembered START + 1) reaches 0.7. Waits until that's true, swaps the tool in a tick before the STOP (like
     * Strict), then gives the server a few ticks before trying again; after 3 tries it falls back to a normal rebreak.
     */
    private void instantRebreak() {
        if (rebreakSentAt != -1) {
            if (clock - rebreakSentAt < 4) return; // waiting for the server's answer
            rebreakSentAt = -1;
            rebreakTries++;
            releaseSilent();
            return;
        }
        int tool = bestTool(BlockUtil.state(rebreakPos));
        int slot = tool == -1 || swap.is("Off") ? mc.player.getInventory().getSelectedSlot() : tool;
        int needed = ticksToBreak(rebreakPos, slot);
        if (needed == Integer.MAX_VALUE) return;
        // a tick or two of slack for the client/server tick offset
        if (clock - serverStartTick + 1 < Math.ceil(needed * 0.7) + 2) return;
        if (slot != mc.player.getInventory().getSelectedSlot() && silentSlot != slot) {
            if (!Prism.guard().canSwitchSlot()) return;
            if (swap.is("Silent")) { InvUtil.silentSwap(slot); silentSlot = slot; }
            else InvUtil.swap(slot, true);
            rebreakSwapped = true;
            return; // STOP next tick, with the tool already in hand for the server
        }
        Direction face = BlockUtil.breakFace(rebreakPos);
        if (face == null) face = rebreakFace;
        if (face == null || !Prism.guard().canPlace()) return;
        mc.player.connection.send(new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, rebreakPos, face));
        mc.player.swing(InteractionHand.MAIN_HAND);
        rebreakSentAt = clock;
        rebreakSwapped = false;
    }

    private void finish() {
        releaseSilent();
        if (swap.is("Normal")) InvUtil.swapBack();
        current = null;
    }

    private void releaseSilent() {
        if (silentSlot == -1 || mc.player == null) return;
        if (Prism.guard().canSwitchSlot()) { InvUtil.silentBack(); silentSlot = -1; }
    }

    private boolean inRange(BlockPos pos) {
        return RotationUtil.distanceToBox(mc.player.getEyePosition(), new AABB(pos)) <= range.get();
    }

    /** Hotbar slot that breaks this block fastest (skips nearly broken tools), or -1 when nothing beats the fist. */
    private int bestTool(BlockState state) {
        int best = -1;
        float bestSpeed = 1f;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() < 5) continue;
            float sp = toolSpeed(s, state);
            if (sp > bestSpeed) { bestSpeed = sp; best = i; }
        }
        return best;
    }

    /** Ticks vanilla needs to break the block holding the item in {@code slot}: Player.getDestroySpeed for any slot. */
    public int ticksToBreak(BlockPos pos, int slot) {
        BlockState state = BlockUtil.state(pos);
        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness < 0) return Integer.MAX_VALUE;
        if (hardness == 0) return 1;
        ItemStack stack = mc.player.getInventory().getItem(slot);
        float s = toolSpeed(stack, state);
        if (MobEffectUtil.hasDigSpeed(mc.player)) s *= 1f + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2f;
        if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            s *= switch (mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
                case 0 -> 0.3f; case 1 -> 0.09f; case 2 -> 0.0027f; default -> 8.1e-4f;
            };
        }
        s *= (float) mc.player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        if (mc.player.isEyeInFluid(FluidTags.WATER)) s *= (float) mc.player.getAttribute(Attributes.SUBMERGED_MINING_SPEED).getValue();
        if (!mc.player.onGround()) s /= 5f;
        boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        float perTick = s / hardness / (correct ? 30 : 100);
        return perTick <= 0 ? Integer.MAX_VALUE : Math.max(1, (int) Math.ceil(1 / perTick));
    }

    /** Item speed plus Efficiency (level^2 + 1, the mining_efficiency attribute it grants when held). */
    private float toolSpeed(ItemStack stack, BlockState state) {
        float s = stack.getDestroySpeed(state);
        if (s > 1f) {
            var eff = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
            int lvl = EnchantmentHelper.getItemEnchantmentLevel(eff, stack);
            if (lvl > 0) s += lvl * lvl + 1;
        }
        return s;
    }

    @Override
    public void onRender3D(PoseStack matrices, float delta) {
        if (current != null) {
            float progress = 0;
            if (current.started) {
                int tool = bestTool(BlockUtil.state(current.pos));
                int slot = tool == -1 || swap.is("Off") ? mc.player.getInventory().getSelectedSlot() : tool;
                int needed = ticksToBreak(current.pos, slot);
                if (needed != Integer.MAX_VALUE) progress = Mth.clamp((current.ticks + delta) / (float) Math.ceil(needed * speed.get()), 0f, 1f);
            }
            int c = ColorUtil.lerp(startColor.color(), doneColor.color(), progress);
            Render3D.box(new AABB(current.pos).deflate(0.5 * (1 - progress)), ColorUtil.withAlpha(c, 60), c, false);
        }
        for (Job j : queue) Render3D.box(new AABB(j.pos), 0, ColorUtil.withAlpha(startColor.color(), 120), false);
        if (!rebreak.is("Off") && rebreakPos != null && current == null)
            Render3D.box(new AABB(rebreakPos), 0, ColorUtil.withAlpha(doneColor.color(), 90), false);
    }

    @Override public String getInfo() { return mode.get() + (queue.isEmpty() ? "" : " +" + queue.size()); }
}
