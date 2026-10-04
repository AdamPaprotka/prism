package dev.prismglass.module.movement;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import java.util.Optional;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;

/**
 * Anti-knockback.
 * <ul>
 *   <li>GrimBudget: cancels knockback completely while staying under a violation budget. From Grim's source
 *       and default config: every ignored knockback is one AntiKB/Explosion violation, the setback (rubberband)
 *       only starts at 10 violations, violations expire after 300 s, and the default punishment for this group
 *       is just an alert at 5 (no kick/ban). Spending at most {@code Budget} cancels per {@code Window} therefore
 *       takes zero knockback for those hits without any setback - and below 5 without even a staff alert on a stock
 *       config. Once the budget is spent it falls back to JumpReset until old violations expire.</li>
 *   <li>JumpReset: jump on the tick knockback lands. 100% vanilla, never flags, reduces KB roughly half.</li>
 *   <li>VerticalOnly / Percent / Cancel: always modify. Flag Grim every hit; meant for other anticheats.</li>
 * </ul>
 */
public class Velocity extends Module {
    public final ModeSetting mode = mode("Mode", "GrimBudget", "GrimBudget = full anti-KB under Grim's setback/alert limits. JumpReset = legit. VerticalOnly/Percent/Cancel = always (flag Grim).",
        "GrimBudget", "JumpReset", "VerticalOnly", "Percent", "Cancel");
    public final NumberSetting budget = num("Budget", 4, 1, 9, 1, "GrimBudget: max cancelled hits per window (stock Grim alerts at 5, setbacks at 10).");
    public final NumberSetting window = num("Window", 300, 30, 600, 10, "GrimBudget: seconds until a violation expires (stock Grim: 300).");
    public final NumberSetting horizontal = num("Horizontal", 0, 0, 100, 1, "Horizontal % kept (Percent mode).");
    public final NumberSetting vertical = num("Vertical", 0, 0, 100, 1, "Vertical % kept (Percent mode).");
    public final BoolSetting explosions = bool("Explosions", true, "Also handle explosion knockback.");
    public final BoolSetting entityPush = bool("NoEntityPush", false, "Not pushed by other entities. [Grim-unsafe: Simulation in melee]");
    public final BoolSetting blockPush = bool("NoBlockPush", false, "Not pushed out of blocks (burrow/phase). [Grim-unsafe]");

    private volatile boolean jumpPending;
    private final java.util.ArrayDeque<Long> spent = new java.util.ArrayDeque<>();

    /** GrimBudget: true (and records the spend) if one more cancel fits in the budget. */
    private boolean spendBudget() {
        long now = System.currentTimeMillis();
        synchronized (spent) {
            while (!spent.isEmpty() && now - spent.peekFirst() > window.get() * 1000) spent.pollFirst();
            if (spent.size() >= budget.getInt()) return false;
            spent.addLast(now);
            return true;
        }
    }

    public int budgetLeft() {
        long now = System.currentTimeMillis();
        synchronized (spent) {
            while (!spent.isEmpty() && now - spent.peekFirst() > window.get() * 1000) spent.pollFirst();
            return budget.getInt() - spent.size();
        }
    }

    public Velocity() {
        super("Velocity", "Reduces knockback.", Category.MOVEMENT);
    }

    @Override
    public void onPacketReceive(PacketEvent event) {
        if (mc.player == null) return;
        if (event.packet instanceof ClientboundSetEntityMotionPacket p && p.id() == mc.player.getId()) {
            switch (mode.get()) {
                case "GrimBudget" -> { if (spendBudget()) event.cancel(); else jumpPending = true; }
                case "Cancel" -> event.cancel();
                case "Percent" -> p.movement = p.movement.multiply(horizontal.get() / 100.0, vertical.get() / 100.0, horizontal.get() / 100.0);
                case "VerticalOnly" -> {
                    p.movement = p.movement.multiply(horizontal.get() / 100.0, 1.0, horizontal.get() / 100.0);
                    jumpPending = true;
                }
                default -> jumpPending = true;
            }
        } else if (explosions.get() && event.packet instanceof ClientboundExplodePacket p && p.playerKnockback().isPresent()) {
            switch (mode.get()) {
                case "GrimBudget" -> { if (spendBudget()) p.playerKnockback = Optional.empty(); else jumpPending = true; }
                case "Cancel" -> p.playerKnockback = Optional.empty();
                case "Percent" -> p.playerKnockback = p.playerKnockback().map(v ->
                    v.multiply(horizontal.get() / 100.0, vertical.get() / 100.0, horizontal.get() / 100.0));
                case "VerticalOnly" -> p.playerKnockback = p.playerKnockback().map(v ->
                    v.multiply(horizontal.get() / 100.0, 1.0, horizontal.get() / 100.0));
                default -> jumpPending = true;
            }
        }
    }

    /** Asked by MovementHooks while building this tick's input. */
    public boolean consumeJumpReset() {
        if (!jumpPending) return false;
        jumpPending = false;
        return mc.player.onGround() && !mc.player.isShiftKeyDown();
    }

    public boolean noEntityPush() { return entityPush.get(); }
    public boolean noBlockPush() { return blockPush.get(); }

    @Override public String getInfo() {
        if (mode.is("GrimBudget")) return "Grim " + budgetLeft() + "/" + budget.getInt();
        return mode.is("Percent") || mode.is("VerticalOnly") ? mode.get() + " " + horizontal.getInt() + "%" : mode.get();
    }
}
