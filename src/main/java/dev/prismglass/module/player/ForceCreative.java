package dev.prismglass.module.player;

import dev.prismglass.event.PacketEvent;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import dev.prismglass.setting.TextSetting;
import dev.prismglass.util.ChatUtil;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;

/**
 * Forces creative mode, client-side or server-side.
 *
 * <p><b>Client</b> is a local spoof: your client believes it is creative (creative inventory, instant
 * break attempts, free flight) while the server still decides whether your actions land. Abilities are
 * set directly, never through {@code onUpdateAbilities()}, so nothing announces "I can fly". Good on a
 * no/lazy anti-cheat server (flight especially); on a strict server edits/far-flight roll back.
 *
 * <p><b>Server</b> makes the game mode <i>real</i> — but only the server can grant that, so this fires
 * known vectors: optionally op yourself first, then run game-mode commands as you. It works only when
 * the server leaves one open (a permissive {@code /gamemode}, a creative-world warp, or an op path).
 * Against a correctly set-up server every attempt is harmlessly rejected.
 *
 * <p><b>Both</b> spoofs locally and tries the server vectors; a genuine server grant then takes over.
 * {@code KeepMode} only drops game-mode changes that pull you <i>off</i> creative, so a real creative
 * grant always applies.
 */
public class ForceCreative extends Module {
    public final ModeSetting side = mode("Side", "Client", "Local spoof, real server grant, or both.", "Client", "Server", "Both");

    // client spoof
    public final BoolSetting flight = bool("Flight", true, "[Client] Also grant local creative flight.");
    public final BoolSetting autoFly = bool("AutoFly", false, "[Client] Start already airborne-flying on enable.");
    public final BoolSetting keepMode = bool("KeepMode", true, "[Client] Ignore the server pulling you OFF creative (client-side).");
    public final BoolSetting keepAbilities = bool("KeepAbilities", true, "[Client] Ignore the server stripping your abilities (keeps flight).");

    // server vectors
    public final BoolSetting tryOp = bool("TryOp", true, "[Server] Try to op yourself before the gamemode commands.");
    public final NumberSetting delay = num("Delay", 10, 1, 100, 1, "[Server] Ticks between command attempts (20 = 1s).");
    public final TextSetting commands = text("Commands",
        "gamemode creative %p;gamemode creative;gmc;gm 1;minecraft:gamemode creative %p;mv gamemode creative;mvtp creative",
        "[Server] Gamemode vectors, ';'-separated. %p = your name.");
    public final TextSetting opCommands = text("OpCommands",
        "op %p;minecraft:op %p;lp user %p permission set * true;pex user %p add *",
        "[Server] Op vectors tried first when TryOp is on. %p = your name.");

    private GameType previous;
    private final Deque<String> queue = new ArrayDeque<>();
    private int timer;

    public ForceCreative() { super("ForceCreative", "Forces creative, client- or server-side.", Category.PLAYER); }

    @Override
    public void onEnable() {
        if (mc.gameMode == null || mc.player == null || mc.getConnection() == null) return;
        previous = mc.gameMode.getPlayerMode();
        if (!side.is("Server")) {
            apply();
            ChatUtil.info("§7Client spoof on — the server still validates your actions.");
        }
        if (!side.is("Client")) {
            if (mc.hasSingleplayerServer()) ChatUtil.info("§7You host this world — use §f/gamemode creative§7 directly.");
            fill();
            timer = 0;
            ChatUtil.info("§7Server vectors firing; they only land on a permissive/vulnerable server.");
        }
    }

    @Override
    public void onDisable() {
        queue.clear();
        if (mc.gameMode == null || mc.player == null) return;
        if (!side.is("Server") && mc.gameMode.getPlayerMode().isCreative() && previous != null && !previous.isCreative()) {
            // only roll our own spoof back; if the server really made us creative, leave it alone
            mc.gameMode.setLocalMode(previous);
            Abilities a = mc.player.getAbilities();
            a.flying = false;
            previous.updatePlayerAbilities(a);
        }
        previous = null;
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        if (!side.is("Server")) apply();
        if (!side.is("Client")) pump();
    }

    // ---- client spoof -----------------------------------------------------------------------

    private void apply() {
        if (mc.gameMode == null || mc.player == null) return;
        if (!mc.gameMode.getPlayerMode().isCreative()) mc.gameMode.setLocalMode(GameType.CREATIVE);
        Abilities a = mc.player.getAbilities();
        a.instabuild = true;
        a.mayBuild = true;
        if (flight.get()) {
            a.mayfly = true;
            if (autoFly.get() && !a.flying) a.flying = true;
        }
    }

    @Override
    public void onPacketReceive(PacketEvent e) {
        if (side.is("Server")) return; // pure server mode: accept whatever the server really grants
        if (keepMode.get() && e.packet instanceof ClientboundGameEventPacket ge
                && ge.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE
                && (int) ge.getParam() != GameType.CREATIVE.getId()) {
            e.cancel(); // only block changes that pull us off creative
            return;
        }
        if (keepAbilities.get() && e.packet instanceof ClientboundPlayerAbilitiesPacket) {
            e.cancel();
        }
    }

    // ---- server vectors ---------------------------------------------------------------------

    private void fill() {
        queue.clear();
        String self = mc.player.getName().getString();
        if (tryOp.get()) addVectors(opCommands.get(), self);
        addVectors(commands.get(), self);
    }

    private void addVectors(String raw, String self) {
        for (String part : raw.split(";")) {
            String cmd = part.trim().replace("%p", self);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isEmpty()) queue.add(cmd);
        }
    }

    private void pump() {
        if (mc.getConnection() == null) return;
        if (mc.gameMode != null && mc.gameMode.getPlayerMode().isCreative() && !mc.hasSingleplayerServer()) {
            if (!queue.isEmpty()) { queue.clear(); ChatUtil.good("Server put you in creative."); }
            return; // already there, stop spamming
        }
        if (queue.isEmpty()) return;
        if (timer++ < delay.getInt()) return;
        timer = 0;
        String cmd = queue.poll();
        mc.getConnection().sendCommand(cmd);
        ChatUtil.info("§8-> §7/" + cmd);
    }

    @Override
    public String getInfo() {
        if (!side.is("Client") && !queue.isEmpty()) return queue.size() + " left";
        return side.is("Server") ? null : (flight.get() ? "fly" : null);
    }
}
