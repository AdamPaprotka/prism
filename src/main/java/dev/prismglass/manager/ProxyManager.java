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

    // ---- per-connection override (bots with their own Tor circuit) --------------------------------------------

    private final Object overrideLock = new Object();
    private volatile java.util.function.Supplier<ChannelHandler> pendingOverride;
    private volatile java.util.concurrent.CountDownLatch overrideTaken;

    /**
     * Run {@code connect} with a one-off proxy handler for the connection it opens. The pipeline hook runs on the
     * Netty thread, so the handler is parked here and the first new client connection takes it; we hold the lock
     * until it did, so two overrides never mix.
     */
    public void connectWith(java.util.function.Supplier<ChannelHandler> override, Runnable connect) throws InterruptedException {
        synchronized (overrideLock) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            overrideTaken = latch;
            pendingOverride = override;
            try {
                connect.run();
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                pendingOverride = null;
                overrideTaken = null;
            }
        }
    }

    /**
     * Netty gives a proxy 10 s to connect by default. Tor often needs more: Minecraft ports are not in most exits'
     * policies (the reduced policy has no 25565, let alone a host's random port), so Tor has to find and build a
     * circuit through an exit that allows the port.
     */
    private static final long PROXY_CONNECT_TIMEOUT_MS = 60_000;

    private static ChannelHandler timed(io.netty.handler.proxy.ProxyHandler h) {
        h.setConnectTimeoutMillis(PROXY_CONNECT_TIMEOUT_MS);
        return h;
    }

    /** Tor circuit of its own: Tor keeps streams with different SOCKS credentials on separate circuits. */
    public ChannelHandler isolatedTorHandler(String tag) {
        PROXIED_CONNECTIONS.incrementAndGet();
        return timed(new Socks5ProxyHandler(new InetSocketAddress(TorRunner.HOST, TorRunner.SOCKS_PORT), "prism-" + tag, "x"));
    }

    /** Handler for a new outgoing connection, or null for direct. Netty thread. */
    public ChannelHandler handler() {
        java.util.function.Supplier<ChannelHandler> o = pendingOverride;
        if (o != null) {
            pendingOverride = null;
            java.util.concurrent.CountDownLatch l = overrideTaken;
            if (l != null) l.countDown();
            return o.get();
        }
        Proxy p = active;
        if (p == null) return null;
        if (p.isTor() && !tor.ready()) tor.start(); // still connects through 9050 once tor is up; the handshake simply waits
        InetSocketAddress addr = new InetSocketAddress(p.host(), p.port());
        PROXIED_CONNECTIONS.incrementAndGet();
        return timed(switch (p.type()) {
            case SOCKS5 -> p.user() == null ? new Socks5ProxyHandler(addr) : new Socks5ProxyHandler(addr, p.user(), p.pass());
            case SOCKS4 -> p.user() == null ? new Socks4ProxyHandler(addr) : new Socks4ProxyHandler(addr, p.user());
            case HTTP -> p.user() == null ? new HttpProxyHandler(addr) : new HttpProxyHandler(addr, p.user(), p.pass());
        });
    }

    // ---- ip check ---------------------------------------------------------------------------------------

    /** Public IP seen by the internet through this proxy (null = direct). Blocking: call off-thread. */
    public static String publicIp(Proxy p) throws IOException {
        // SOCKS5 with credentials: do the handshake ourselves. Java's built-in SOCKS client may skip the username, and
        // with Tor the username IS the circuit, so a bot's "own circuit" IP must be asked with exactly its credentials.
        if (p != null && p.type() == Type.SOCKS5 && p.user() != null) return ipViaSocks5(p);
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

    private static String ipViaSocks5(Proxy p) throws IOException {
        String host = "api.ipify.org";
        try (java.net.Socket raw = new java.net.Socket()) {
            raw.connect(new InetSocketAddress(p.host(), p.port()), 10000);
            raw.setSoTimeout(20000);
            java.io.DataOutputStream out = new java.io.DataOutputStream(raw.getOutputStream());
            java.io.DataInputStream in = new java.io.DataInputStream(raw.getInputStream());
            out.write(new byte[]{5, 1, 2}); // SOCKS5, one method: username/password only
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 2) throw new IOException("proxy refused username/password auth");
            byte[] u = p.user().getBytes(StandardCharsets.UTF_8), pw = (p.pass() == null ? "" : p.pass()).getBytes(StandardCharsets.UTF_8);
            out.writeByte(1); out.writeByte(u.length); out.write(u); out.writeByte(pw.length); out.write(pw);
            if (in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0) throw new IOException("proxy rejected the credentials");
            byte[] h = host.getBytes(StandardCharsets.US_ASCII);
            out.write(new byte[]{5, 1, 0, 3}); out.writeByte(h.length); out.write(h); out.writeShort(443);
            if (in.readUnsignedByte() != 5) throw new IOException("bad SOCKS reply");
            int rep = in.readUnsignedByte();
            if (rep != 0) throw new IOException("SOCKS connect failed (" + rep + ")");
            in.readUnsignedByte();
            int atyp = in.readUnsignedByte();
            int skip = atyp == 1 ? 4 : atyp == 4 ? 16 : in.readUnsignedByte();
            in.skipNBytes(skip + 2);
            javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
            javax.net.ssl.SSLParameters sp = tls.getSSLParameters();
            sp.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
            tls.setSSLParameters(sp);
            tls.startHandshake();
            tls.getOutputStream().write(("GET /?format=text HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: PrismGlass\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            String response = new String(tls.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            int body = response.indexOf("\r\n\r\n");
            if (body < 0 || !response.startsWith("HTTP/1.1 200")) throw new IOException("unexpected answer from ipify");
            String ip = response.substring(body + 4).trim();
            if (!ip.matches("[0-9a-fA-F.:]+")) throw new IOException("unexpected body from ipify");
            return ip;
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
