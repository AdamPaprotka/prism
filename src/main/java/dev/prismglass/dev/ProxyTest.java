package dev.prismglass.dev;

import dev.prismglass.Prism;
import dev.prismglass.manager.ProxyManager;
import dev.prismglass.manager.TorRunner;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * Dev only (-Pproxytest), title screen, real network: starts Tor through ProxyManager, waits for bootstrap, compares
 * the public IP seen through Tor with the direct one, then pings a public server so the Netty proxy handler is
 * exercised end to end, asks for a new identity, screenshots the Proxies screen and exits. Logs "[proxytest]".
 */
public final class ProxyTest {
    private static final Minecraft mc = Minecraft.getInstance();
    private static int ticks, passed, failed, pingState;
    private static boolean started, ipChecked, pinged, shot;
    private static long readyAt;
    private static String torIp;

    private ProxyTest() {}

    public static void install() { ClientTickEvents.END_CLIENT_TICK.register(c -> tick()); }

    private static void log(String s) { Prism.LOG.info("[proxytest] {}", s); }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else failed++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void finish() {
        Prism.proxies().tor().stop();
        log("passed " + passed + " failed " + failed);
        log(failed == 0 ? "ALL PASS" : "SOME FAILED");
        Runtime.getRuntime().halt(0);
    }

    private static void tick() {
        ticks++;
        ProxyManager pm = Prism.proxies();
        TorRunner tor = pm.tor();
        if (ticks == 60) {
            started = true;
            log("tor.exe: " + TorRunner.findTor());
            check("Tor entry exists", pm.get(ProxyManager.TOR) != null);
            check("use(Tor) starts tor", pm.use(ProxyManager.TOR) && tor.running());
            log("status: " + tor.status());
        }
        if (!started) return;
        if (ticks % 100 == 0 && !tor.ready()) log("bootstrap " + tor.bootstrap() + "% - " + tor.status());
        if (ticks > 60 + 20 * 150 && !tor.ready()) { check("Tor bootstrapped within 150 s", false); finish(); }
        if (!tor.ready()) return;
        if (readyAt == 0) { readyAt = ticks; check("Tor bootstrapped", true); }

        if (!ipChecked) {
            ipChecked = true;
            Thread t = new Thread(() -> {
                try {
                    torIp = ProxyManager.publicIp(pm.active());
                    String direct = ProxyManager.publicIp(null);
                    log("ip through tor: " + torIp);
                    check("IP through Tor differs from direct", torIp != null && !torIp.isEmpty() && !torIp.equals(direct));
                } catch (Exception e) {
                    check("IP check (" + e + ")", false);
                }
                mc.execute(() -> pingState = 1);
            }, "proxytest-ip");
            t.setDaemon(true);
            t.start();
        }
        if (pingState == 1 && !pinged) {
            pinged = true;
            pingState = 2;
            int before = ProxyManager.PROXIED_CONNECTIONS.get();
            try {
                ServerData sd = new ServerData("proxytest", "mc.hypixel.net", ServerData.Type.OTHER);
                new ServerStatusPinger().pingServer(sd,
                    () -> mc.execute(() -> {
                        log("ping ok: " + sd.motd.getString().replace('\n', ' ') + " / " + sd.ping + " ms");
                        check("server status received through the proxy", true);
                        check("proxy handler was added to the connection", ProxyManager.PROXIED_CONNECTIONS.get() > before);
                        pingState = 3;
                    }),
                    () -> mc.execute(() -> { if (pingState == 2) { check("server ping through Tor (failed: " + sd.status.getString() + ")", false); pingState = 3; } }), // also fires after success
                    EventLoopGroupHolder.remote(false));
            } catch (Exception e) {
                check("ping start (" + e + ")", false);
                pingState = 3;
            }
        }
        if (pingState == 2 && ticks - readyAt > 20 * 60) { check("ping answered within 60 s", false); pingState = 3; }
        if (pingState == 3 && !shot) {
            shot = true;
            check("new identity accepted", tor.newIdentity());
            mc.setScreen(new dev.prismglass.gui.ProxiesScreen(null));
            pingState = 4;
            readyAt = ticks;
        }
        if (pingState == 4 && ticks - readyAt == 30) Screenshot.grab(mc.gameDirectory, "prism-proxies.png", mc.getMainRenderTarget(), 1, msg -> log(msg.getString()));
        if (pingState == 4 && ticks - readyAt == 50) finish();
    }
}
