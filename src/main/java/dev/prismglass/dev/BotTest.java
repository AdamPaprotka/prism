package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.manager.BotPlayers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Dev only (-Pbottest -Pmp=host:port against an offline-mode server): .joinplayer makes a real second connection,
 * the server lists it (it shows up in OUR tab list), .sayplayer chats and runs a command as it, .leaveplayer drops
 * it. Logs "[bottest]" lines; the server console is the other half of the proof.
 */
public final class BotTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int t, passed, failed;
    private static String oldName;
    private static boolean switched;

    private BotTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[bottest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void tick() {
        mc.options.pauseOnLostFocus = false;
        if (mc.player == null || mc.getConnection() == null || mc.isLocalServer()) return;
        t++;
        BotPlayers bots = Prism.bots();
        if (t == 40) {
            log("joined as " + mc.player.getName().getString() + " on " + mc.getCurrentServer().ip);
            if (oldName == null) oldName = mc.player.getName().getString();
            Prism.commands().handle(".joinplayer PrismBot");
        }
        if (t == 60) Prism.commands().handle(".joinplayer PrismBot2");
        if (t == 200) {
            var b = bots.get("PrismBot");
            log("bot state: " + (b == null ? "none" : b.state()) + ", bots: " + bots.names());
            check("bot is in the game", b != null && b.inGame());
            check("server lists the bot in our tab list", mc.getConnection().getPlayerInfo("PrismBot") != null);
            check("second bot also in", mc.getConnection().getPlayerInfo("PrismBot2") != null);
            check("bot entity visible near us", mc.level.players().stream().anyMatch(p -> p.getName().getString().equals("PrismBot")));
            Prism.commands().handle(".sayplayer PrismBot hello from the bot");
            Prism.commands().handle(".sayplayer all /me waves");
        }
        if (t == 260) Screenshot.grab(mc.gameDirectory, "prism-bots.png", mc.gameRenderer.mainRenderTarget(), 1, msg -> log(msg.getString()));
        if (t == 280) Prism.commands().handle(".leaveplayer PrismBot");
        if (t == 340) {
            check("bot gone after leave", bots.get("PrismBot") == null && mc.getConnection().getPlayerInfo("PrismBot") == null);
            check("other bot still there", mc.getConnection().getPlayerInfo("PrismBot2") != null);
            Prism.commands().handle(".switchplayer PrismBot2"); // we become PrismBot2, a bot takes oldName
            switched = true;
        }
        if (switched && t == 480) {
            log("now playing as " + mc.player.getName().getString() + ", user " + mc.getUser().getName() + ", bots " + bots.names());
            check("client is now the bot's account", mc.player.getName().getString().equals("PrismBot2"));
            check("old name came back as a bot", bots.get(oldName) != null && bots.get(oldName).inGame());
            check("server lists the old name", mc.getConnection().getPlayerInfo(oldName) != null);
            Prism.commands().handle(".sayplayer " + oldName + " i am the bot now");
        }
        if (switched && t == 520) {
            bots.leaveAll();
            Prism.accounts().loginOriginal();
        }
        if (switched && t == 560) {
            log("passed " + passed + " failed " + failed);
            log(failed == 0 ? "ALL PASS" : "SOME FAILED");
            Runtime.getRuntime().halt(0);
        }
    }
}
