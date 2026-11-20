package dev.prismglass.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Minimal Discord Rich Presence client over Discord's local IPC (no library): the named pipe
 * {@code \\.\pipe\discord-ipc-N} on Windows, the unix socket {@code discord-ipc-N} elsewhere.
 * Frame = op (int32 LE) + length (int32 LE) + UTF-8 JSON. Every command gets exactly one reply, which is read
 * straight after the write: a separate blocking reader would deadlock writes on a synchronous Windows pipe handle.
 * Not thread-safe: one worker thread owns an instance.
 */
public final class DiscordIpc implements Closeable {
    private static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2;

    private interface Pipe extends Closeable {
        void write(byte[] b) throws IOException;
        void readFully(byte[] b) throws IOException;
    }

    private Pipe pipe;
    /** Discord user the client is connected as (from READY), for the status line. */
    private String user;

    public boolean connected() { return pipe != null; }
    public String user() { return user; }

    /** Opens the first Discord pipe that answers and handshakes. @throws IOException with a readable reason. */
    public void connect(String clientId) throws IOException {
        close();
        IOException last = null;
        for (int i = 0; i < 10; i++) {
            Pipe p;
            try { p = open(i); } catch (IOException e) { last = e; continue; }
            if (p == null) continue;
            pipe = p;
            try {
                JsonObject hello = new JsonObject();
                hello.addProperty("v", 1);
                hello.addProperty("client_id", clientId);
                send(OP_HANDSHAKE, hello);
                Frame r = read();
                if (r.op == OP_CLOSE) throw new IOException("Discord refused: " + message(r.json));
                if (r.json.has("data") && r.json.getAsJsonObject("data").has("user")) {
                    var u = r.json.getAsJsonObject("data").getAsJsonObject("user");
                    user = u.has("global_name") && !u.get("global_name").isJsonNull() ? u.get("global_name").getAsString() : u.get("username").getAsString();
                }
                return;
            } catch (IOException e) {
                close();
                throw e;
            }
        }
        throw last != null ? last : new IOException("Discord isn't running");
    }

    /** Sets the presence; {@code activity} null clears it. */
    public void setActivity(JsonObject activity) throws IOException {
        if (pipe == null) throw new IOException("not connected");
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) args.add("activity", activity);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        send(OP_FRAME, cmd);
        Frame r = read();
        if (r.op == OP_CLOSE) { close(); throw new IOException("Discord closed the connection: " + message(r.json)); }
        if (r.json.has("evt") && "ERROR".equals(r.json.get("evt").getAsString())) throw new IOException("Discord: " + message(r.json.getAsJsonObject("data")));
    }

    @Override
    public void close() {
        if (pipe != null) try { pipe.close(); } catch (IOException ignored) {}
        pipe = null;
    }

    // ---- framing ---------------------------------------------------------------------------------------

    private record Frame(int op, JsonObject json) {}

    private void send(int op, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(op).putInt(body.length).put(body);
        try { pipe.write(b.array()); } catch (IOException e) { close(); throw e; }
    }

    private Frame read() throws IOException {
        try {
            byte[] head = new byte[8];
            pipe.readFully(head);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int op = h.getInt(), len = h.getInt();
            if (len < 0 || len > 1 << 20) throw new IOException("bad frame length " + len);
            byte[] body = new byte[len];
            pipe.readFully(body);
            String s = new String(body, StandardCharsets.UTF_8);
            return new Frame(op, s.isBlank() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject());
        } catch (IOException e) {
            close();
            throw e;
        } catch (RuntimeException e) {
            close();
            throw new IOException("bad reply from Discord", e);
        }
    }

    private static String message(JsonObject o) {
        if (o == null) return "?";
        String code = o.has("code") ? o.get("code").getAsString() + " " : "";
        return code + (o.has("message") ? o.get("message").getAsString() : o.toString());
    }

    // ---- transports --------------------------------------------------------------------------------------

    private static Pipe open(int i) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            RandomAccessFile f;
            try { f = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw"); }
            catch (java.io.FileNotFoundException e) { return null; } // no Discord on this pipe number
            return new Pipe() {
                public void write(byte[] b) throws IOException { f.write(b); }
                public void readFully(byte[] b) throws IOException { f.readFully(b); }
                public void close() throws IOException { f.close(); }
            };
        }
        for (Path dir : unixDirs()) {
            Path sock = dir.resolve("discord-ipc-" + i);
            if (!Files.exists(sock)) continue;
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            try { ch.connect(UnixDomainSocketAddress.of(sock)); }
            catch (IOException e) { ch.close(); continue; }
            return new Pipe() {
                public void write(byte[] b) throws IOException { ByteBuffer bb = ByteBuffer.wrap(b); while (bb.hasRemaining()) ch.write(bb); }
                public void readFully(byte[] b) throws IOException {
                    ByteBuffer bb = ByteBuffer.wrap(b);
                    while (bb.hasRemaining()) if (ch.read(bb) < 0) throw new IOException("Discord closed the socket");
                }
                public void close() throws IOException { ch.close(); }
            };
        }
        return null;
    }

    private static List<Path> unixDirs() {
        List<Path> out = new ArrayList<>();
        for (String env : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) out.add(Path.of(v));
        }
        out.add(Path.of("/tmp"));
        List<Path> all = new ArrayList<>();
        for (Path d : out) { all.add(d); all.add(d.resolve("app/com.discordapp.Discord")); all.add(d.resolve("snap.discord")); }
        return all;
    }
}
