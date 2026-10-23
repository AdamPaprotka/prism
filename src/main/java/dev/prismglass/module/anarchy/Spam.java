package dev.prismglass.module.anarchy;

import dev.prismglass.Prism;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.ChatUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Spams chat with your messages: from Messages (separated by |) or, with FromFile, prism/spam.txt (one per line).
 * Lines starting with / run as commands. {player} becomes a random other player from the tab list, {number} a random
 * number. AntiDuplicate appends a short random tag, since servers drop a message identical to the last one.
 *
 * <p>AntiKick mirrors vanilla's chat throttle (ServerGamePacketListenerImpl.chatSpamThrottler: +20 per message,
 * -1 per tick, kick at 200) and holds a message back while it would get close, so a short Delay bursts and then
 * settles at the fastest rate that never kicks (one per second). Plugins can be stricter. Messages are never logged.
 */
public class Spam extends Module {
    public final TextSetting messages = text("Messages", "Prism Glass on top|get good, get Prism", "Messages separated by |. Lines starting with / are commands.");
    public final BoolSetting fromFile = bool("FromFile", false, "Read prism/spam.txt instead (one message per line, created for you).");
    public final NumberSetting delay = num("Delay", 3, 0.1, 60, 0.1, "Seconds between messages.");
    public final ModeSetting order = mode("Order", "Sequential", "Which message comes next.", "Sequential", "Random");
    public final BoolSetting antiDuplicate = bool("AntiDuplicate", true, "Add a random tag so no two messages are identical.");
    public final NumberSetting tagLength = num("TagLength", 4, 1, 12, 1, "AntiDuplicate tag length.").visibleWhen(() -> antiDuplicate.get());
    public final BoolSetting antiKick = bool("AntiKick", true, "Never send faster than the vanilla spam kick allows.");
    public final BoolSetting stopOnLeave = bool("StopOnLeave", true, "Turn off when you leave the server.");

    private final Random random = new Random();
    private int index, cooldown, score;
    /** Messages sent since enabled (dev checks). */
    public int sent;

    public Spam() { super("Spam", "Spams chat with your messages (anti-kick, anti-duplicate, {player}).", Category.ANARCHY); }

    @Override public void onEnable() { index = 0; cooldown = 0; sent = 0; }

    @Override
    public void onWorldJoin() {
        score = 0;
        if (stopOnLeave.get() && isEnabled()) setEnabled(false);
    }

    @Override
    public void onTick() {
        if (score > 0) score--;
        if (cooldown > 0) { cooldown--; return; }
        if (antiKick.get() && score + 20 >= 180) return; // the server would be at its limit after this one
        List<String> list = lines();
        if (list.isEmpty()) {
            ChatUtil.error(fromFile.get() ? "Spam: prism/spam.txt is empty" : "Spam: no messages");
            setEnabled(false);
            return;
        }
        String msg = list.get(order.is("Random") ? random.nextInt(list.size()) : index++ % list.size());
        msg = fill(msg);
        if (antiDuplicate.get() && !msg.startsWith("/")) msg = msg + " " + tag();
        if (msg.length() > 256) msg = msg.substring(0, 256);
        if (msg.startsWith("/")) mc.player.connection.sendCommand(msg.substring(1));
        else mc.player.connection.sendChat(msg);
        score += 20;
        sent++;
        cooldown = Math.max(1, (int) Math.round(delay.get() * 20));
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        if (fromFile.get()) {
            Path file = Prism.config().folder().resolve("spam.txt");
            try {
                if (!Files.exists(file)) Files.writeString(file, "Prism Glass on top\nget good, get Prism\n");
                for (String l : Files.readAllLines(file)) if (!l.isBlank()) out.add(l.strip());
            } catch (Exception e) {
                Prism.LOG.error("[spam] could not read spam.txt", e);
            }
        } else {
            for (String l : messages.get().split("\\|")) if (!l.isBlank()) out.add(l.strip());
        }
        return out;
    }

    private String fill(String msg) {
        if (msg.contains("{player}")) {
            List<String> names = new ArrayList<>();
            for (var info : mc.player.connection.getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (!name.equals(mc.player.getGameProfile().name())) names.add(name);
            }
            msg = msg.replace("{player}", names.isEmpty() ? "you" : names.get(random.nextInt(names.size())));
        }
        while (msg.contains("{number}")) msg = msg.replaceFirst("\\{number}", String.valueOf(random.nextInt(1000)));
        return msg;
    }

    private String tag() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(tagLength.getInt());
        for (int i = 0; i < tagLength.getInt(); i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }
}
