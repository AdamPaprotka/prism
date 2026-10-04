package dev.prismglass.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ChatUtil {
    private ChatUtil() {}

    private static MutableComponent prefix() {
        return Component.literal("").append(Component.literal("◆ ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal("Prism ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
            .append(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY));
    }

    public static void info(String msg) { out(msg, ChatFormatting.GRAY, 0xFFC8D0DC); }
    public static void good(String msg) { out(msg, ChatFormatting.GREEN, 0xFF7CFF9A); }
    public static void error(String msg) { out(msg, ChatFormatting.RED, 0xFFFF7A7A); }

    /** Chat is part of the captured frame, so with StreamProof on Prism talks through hidden HUD toasts instead. */
    private static void out(String msg, ChatFormatting style, int toastColor) {
        if (dev.prismglass.streamproof.Overlay.active()) {
            String plain = msg.replaceAll("§.", "");
            if (!dev.prismglass.module.client.Hud.message(plain, toastColor)) dev.prismglass.Prism.LOG.info("[Prism] {}", plain);
            return;
        }
        send(prefix().append(Component.literal(msg).withStyle(style)));
    }

    public static void send(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) mc.gui.getChat().addClientSystemMessage(text);
    }
}
