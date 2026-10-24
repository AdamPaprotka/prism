package dev.prismglass.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;

/**
 * Hand swings. 26.3 dropped ServerboundSwingPacket and the hand-only {@code LivingEntity.swing(hand)}:
 * <ul>
 * <li>after a use / place / interact the server swings by itself from the use packet, so the client only
 *     plays the animation ({@link #use});</li>
 * <li>attacks, mining hits and bare punches send {@link ServerboundPunchPacket} (main hand only; the server
 *     swings and resets the attack-strength ticker), like vanilla {@code startAttack}/{@code continueAttack}
 *     ({@link #punch}).</li>
 * </ul>
 */
public final class SwingUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private SwingUtil() {}

    /** Local swing animation for a use/place/interact with {@code hand} (the server swings from the use packet). */
    public static void use(InteractionHand hand) {
        if (mc.player == null) return;
        mc.player.swing(hand, mc.player.getItemInHand(hand).getInteractAnimation(), false);
    }

    /** Main-hand attack swing: animation plus the punch packet (the old swing packet for attacks and digging). */
    public static void punch() {
        if (mc.player == null) return;
        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), false);
        mc.player.connection.send(ServerboundPunchPacket.INSTANCE);
    }

    /** Only the punch packet, no local animation (the old "silent" swing packet). */
    public static void punchPacket() {
        if (mc.player != null) mc.player.connection.send(ServerboundPunchPacket.INSTANCE);
    }
}
