package dev.prismglass.module.player;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.setting.TextSetting;
import dev.prismglass.util.ChatUtil;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Attempts to grant yourself operator by firing known privilege-escalation command vectors.
 *
 * <p>Operator status is decided entirely by the server, so there is no magic client packet that ops
 * you. This only works against a <b>misconfigured or vulnerable</b> server: one that leaves a perms
 * plugin command open to everyone, hands default players console-level perms, or runs a plugin with a
 * command-injection bug. Against a correctly set-up server every attempt is simply rejected — it does
 * no harm, it just does nothing.
 *
 * <p>The vectors are plain commands sent as you, {@code %p} filled with your name, one every
 * {@code Delay} ticks (burst-sending them trips command rate limits and gets you kicked).
 * Edit {@code Commands} ({@code ;}-separated) to target a specific server's setup.
 */
public class ForceOP extends Module {
    public final ModeSetting mode = mode("Mode", "Once", "Run the list once, or keep retrying.", "Once", "Repeat");
    public final NumberSetting delay = num("Delay", 10, 1, 100, 1, "Ticks between attempts (20 ticks = 1s).");
    public final TextSetting commands = text("Commands",
        "op %p;minecraft:op %p;lp user %p permission set * true;pex user %p add *;perm player %p permission add *",
        "Vectors to try, ';'-separated. %p = your name.");

    private final Deque<String> queue = new ArrayDeque<>();
    private int timer;

    public ForceOP() { super("ForceOP", "Tries known vectors to op yourself.", Category.PLAYER); }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.getConnection() == null) return;
        if (mc.hasSingleplayerServer()) {
            ChatUtil.info("§7You host this world — use §f/op§7 directly.");
        }
        fill();
        timer = 0;
        ChatUtil.info("§7Only works on a misconfigured/vulnerable server; rejections are harmless.");
    }

    @Override public void onDisable() { queue.clear(); }

    private void fill() {
        queue.clear();
        String self = mc.player.getName().getString();
        for (String raw : commands.get().split(";")) {
            String cmd = raw.trim().replace("%p", self);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isEmpty()) queue.add(cmd);
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.getConnection() == null) return;
        if (queue.isEmpty()) {
            if (mode.is("Repeat")) fill();
            else { setEnabled(false); return; }
            if (queue.isEmpty()) { setEnabled(false); return; }
        }
        if (timer++ < delay.getInt()) return;
        timer = 0;
        String cmd = queue.poll();
        mc.getConnection().sendCommand(cmd);
        ChatUtil.info("§8-> §7/" + cmd);
    }

    @Override public String getInfo() { return queue.isEmpty() ? null : queue.size() + " left"; }
}
