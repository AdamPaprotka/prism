package dev.prismglass.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.prismglass.Prism;
import io.netty.channel.ChannelHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Proxies for the game connection (Meteor-style): SOCKS5, SOCKS4 or HTTP, one active at a time, pushed into every
 * new server connection's Netty pipeline (see ClientConnectionMixin). The "Tor" entry is special: using it starts
 * tor.exe through {@link TorRunner}. Only the server connection is proxied; the Mojang session check for a premium
 * account still goes out directly, and the server's hostname is resolved locally before the proxy sees it.
 */
public final class ProxyManager {
    public enum Type { SOCKS5, SOCKS4, HTTP }

    /** user/pass may be null. */
    public record Proxy(String name, Type type, String host, int port, String user, String pass) {
        public String address() { return host + ":" + port; }
        public boolean isTor() { return name.equalsIgnoreCase("Tor"); }
    }

    public static final String TOR = "Tor";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Connections that got a proxy handler (dev tests read it). */
    public static final AtomicInteger PROXIED_CONNECTIONS = new AtomicInteger();

    private final Path file = FabricLoader.getInstance().getGameDir().resolve("prism").resolve("proxies.json");
    private final List<Proxy> proxies = new ArrayList<>();
    private final Map<String, String> accountProxy = new LinkedHashMap<>();
    private final TorRunner tor = new TorRunner();
    private volatile Proxy active;

    public ProxyManager() {
        load();
        if (get(TOR) == null) proxies.add(0, new Proxy(TOR, Type.SOCKS5, TorRunner.HOST, TorRunner.SOCKS_PORT, null, null));
    }

    public TorRunner tor() { return tor; }
    public List<Proxy> all() { return proxies; }
    public Proxy active() { return active; }

    public Proxy get(String name) {
        for (Proxy p : proxies) if (p.name().equalsIgnoreCase(name)) return p;
        return null;
    }

    /** Adds or replaces (same name). */
    public void put(Proxy p) {
        proxies.removeIf(o -> o.name().equalsIgnoreCase(p.name()));
        proxies.add(p);
        if (active != null && active.name().equalsIgnoreCase(p.name())) active = p;
        save();
    }

    public boolean remove(String name) {
        if (name.equalsIgnoreCase(TOR)) return false;
        boolean r = proxies.removeIf(o -> o.name().equalsIgnoreCase(name));
        if (active != null && active.name().equalsIgnoreCase(name)) active = null;
        accountProxy.values().removeIf(v -> v.equalsIgnoreCase(name));
        if (r) save();
        return r;
    }

    /** Make this proxy the one new connections use; Tor is started if needed. null = direct. */
    public boolean use(String name) {
        if (name == null) { active = null; return true; }
        Proxy p = get(name);
        if (p == null) return false;
        active = p;
        if (p.isTor() && !tor.running()) tor.start();
        return true;
    }

    /** "host:port" with an optional scheme-less type, e.g. "1.2.3.4:1080". */
    public static Proxy parse(String name, String hostPort, String type, String user, String pass) {
        int c = hostPort.lastIndexOf(':');
        if (c <= 0) throw new IllegalArgumentException("Use host:port");
        int port = Integer.parseInt(hostPort.substring(c + 1));
        Type t = type == null ? Type.SOCKS5 : Type.valueOf(type.toUpperCase(Locale.ROOT));
        return new Proxy(name, t, hostPort.substring(0, c), port, user, pass);
    }

    // ---- per account ------------------------------------------------------------------------------------

    public void setAccountProxy(String account, String proxy) {
        if (proxy == null) accountProxy.remove(AccountManager.key(account));
        else accountProxy.put(AccountManager.key(account), proxy);
        save();
    }

    public String accountProxy(String account) { return accountProxy.get(AccountManager.key(account)); }

    /** AccountManager.login: switch to that account's proxy when it has one. */
    public void onLogin(String account) {
        String p = accountProxy(account);
        if (p != null) use(p);
    }

    // ---- netty ------------------------------------------------------------------------------------------

    /** Handler for a new outgoing connection, or null for direct. Netty thread. */
    public ChannelHandler handler() {
        Proxy p = active;
        if (p == null) return null;
        if (p.isTor() && !tor.ready()) tor.start(); // still connects through 9050 once tor is up; the handshake simply waits
        InetSocketAddress addr = new InetSocketAddress(p.host(), p.port());
        PROXIED_CONNECTIONS.incrementAndGet();
        return switch (p.type()) {
            case SOCKS5 -> p.user() == null ? new Socks5ProxyHandler(addr) : new Socks5ProxyHandler(addr, p.user(), p.pass());
            case SOCKS4 -> p.user() == null ? new Socks4ProxyHandler(addr) : new Socks4ProxyHandler(addr, p.user());
            case HTTP -> p.user() == null ? new HttpProxyHandler(addr) : new HttpProxyHandler(addr, p.user(), p.pass());
        };
    }

    // ---- ip check ---------------------------------------------------------------------------------------

    /** Public IP seen by the internet through this proxy (null = direct). Blocking: call off-thread. */
    public static String publicIp(Proxy p) throws IOException {
        java.net.Proxy jp = p == null ? java.net.Proxy.NO_PROXY
            : new java.net.Proxy(p.type() == Type.HTTP ? java.net.Proxy.Type.HTTP : java.net.Proxy.Type.SOCKS, new InetSocketAddress(p.host(), p.port()));
        if (p != null && p.user() != null) {
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(p.user(), (p.pass() == null ? "" : p.pass()).toCharArray());
                }
            });
        }
        HttpURLConnection c = (HttpURLConnection) URI.create("https://api.ipify.org/?format=text").toURL().openConnection(jp);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "PrismGlass");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            return r.readLine().trim();
        } finally {
            c.disconnect();
        }
    }

    // ---- storage ----------------------------------------------------------------------------------------

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var e : o.getAsJsonArray("proxies")) {
                JsonObject j = e.getAsJsonObject();
                proxies.add(new Proxy(j.get("name").getAsString(), Type.valueOf(j.get("type").getAsString()), j.get("host").getAsString(),
                    j.get("port").getAsInt(), j.has("user") ? j.get("user").getAsString() : null, j.has("pass") ? j.get("pass").getAsString() : null));
            }
            if (o.has("accounts")) for (var e : o.getAsJsonObject("accounts").entrySet()) accountProxy.put(e.getKey(), e.getValue().getAsString());
        } catch (Exception e) {
            Prism.LOG.warn("Could not read proxies.json", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            for (Proxy p : proxies) {
                JsonObject j = new JsonObject();
                j.addProperty("name", p.name());
                j.addProperty("type", p.type().name());
                j.addProperty("host", p.host());
                j.addProperty("port", p.port());
                if (p.user() != null) j.addProperty("user", p.user());
                if (p.pass() != null) j.addProperty("pass", p.pass());
                a.add(j);
            }
            o.add("proxies", a);
            JsonObject acc = new JsonObject();
            accountProxy.forEach(acc::addProperty);
            o.add("accounts", acc);
            Files.writeString(file, GSON.toJson(o));
        } catch (Exception e) {
            Prism.LOG.warn("Could not write proxies.json", e);
        }
    }
}
