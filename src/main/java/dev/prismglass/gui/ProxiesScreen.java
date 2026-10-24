package dev.prismglass.gui;

import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.manager.ProxyManager;
import dev.prismglass.manager.TorRunner;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Proxies ({@code .proxy} or the Accounts screen): pick the proxy the next server connection goes through, run Tor
 * from here, get a new Tor identity, and check which IP the internet sees. Add a proxy as
 * "name host:port [socks5|socks4|http] [user pass]".
 */
public class ProxiesScreen extends Screen {
    private static final int TEXT = 0xFFF2F6FC, DIM = 0xFFD5DEEA, FAINT = 0xFF9FAEC2;
    private static final int ROW_H = 18, MAX_ROWS = 7;

    private final @Nullable Screen parent;
    private final List<float[]> rects = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private final long openedAt = System.currentTimeMillis();
    private String input = "";
    private boolean inputFocused;
    private int scroll;
    private volatile String flash;
    private volatile long flashAt;
    private volatile boolean checking;

    public ProxiesScreen(@Nullable Screen parent) {
        super(Component.literal("Proxies"));
        this.parent = parent;
    }

    @Override public boolean isPauseScreen() { return false; }

    private void flash(String s) { flash = s; flashAt = System.currentTimeMillis(); }

    private void add() {
        String[] a = input.trim().split("\\s+");
        if (a.length < 2) { flash("name host:port [socks5|socks4|http] [user pass]"); return; }
        try {
            Prism.proxies().put(ProxyManager.parse(a[0], a[1], a.length > 2 ? a[2] : null, a.length > 3 ? a[3] : null, a.length > 4 ? a[4] : null));
            input = "";
            flash("Added " + a[0]);
        } catch (RuntimeException e) {
            flash("Invalid: " + e.getMessage());
        }
    }

    /** Looks up the public IP through the active proxy on a worker thread. */
    private void checkIp() {
        if (checking) return;
        checking = true;
        ProxyManager.Proxy p = Prism.proxies().active();
        flash(p == null ? "Checking direct IP..." : "Checking IP through " + p.name() + "...");
        Thread t = new Thread(() -> {
            try {
                String ip = ProxyManager.publicIp(p);
                if (p == null) flash("Direct: " + ip);
                else {
                    String direct = null;
                    try { direct = ProxyManager.publicIp(null); } catch (Exception ignored) {}
                    flash("Through " + p.name() + ": " + ip + (direct == null ? "" : direct.equals(ip) ? "  (same as direct!)" : "  (different from your real IP)"));
                }
            } catch (Exception e) {
                flash("IP check failed: " + e.getMessage());
            } finally {
                checking = false;
            }
        }, "prism-ipcheck");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        dev.prismglass.streamproof.Overlay.markGui();
        rects.clear();
        actions.clear();
        ClickGui theme = Prism.modules().get(ClickGui.class);
        ProxyManager pm = Prism.proxies();
        TorRunner tor = pm.tor();
        List<ProxyManager.Proxy> list = pm.all();
        ProxyManager.Proxy active = pm.active();
        int acc = theme.accent.color();
        float in = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        int rows = Math.max(1, Math.min(MAX_ROWS, list.size()));
        scroll = Math.max(0, Math.min(scroll, list.size() - MAX_ROWS));
        float w = Math.min(360, width - 32), h = 96 + rows * ROW_H + 46;
        float x = (width - w) / 2f, y = (height - h) / 2f + (1 - in) * 10;
        Glass.panel(ctx, x, y, w, h, theme.panelStyle());

        // header
        ctx.text(font, "Proxies", (int) x + 14, (int) y + 12, TEXT, true);
        String badge = active == null ? "direct" : active.name();
        int bc = active == null ? 0xFF4ADE80 : acc;
        float bw = font.width(badge) + 10, bx = x + w - 14 - bw;
        Glass.pill(ctx, bx, y + 10, bw, 11, ColorUtil.withAlpha(bc, 90), ColorUtil.withAlpha(bc, 60), 0.6f);
        ctx.text(font, badge, (int) (bx + 5), (int) y + 12, bc, false);
        ctx.fill((int) x + 12, (int) y + 27, (int) (x + w - 12), (int) y + 28, 0x30FFFFFF);

        // Tor row: status + controls
        String ts = tor.ready() ? "Tor connected" : tor.running() ? "Tor " + tor.status() : TorRunner.findTor() == null ? "Tor not installed" : "Tor " + tor.status();
        int tc = tor.ready() ? 0xFF4ADE80 : tor.running() ? 0xFFFACC15 : FAINT;
        ctx.text(font, font.plainSubstrByWidth(ts, (int) w - 28 - 150), (int) x + 14, (int) y + 36, tc, true);
        boolean missing = !tor.running() && TorRunner.findTor() == null;
        button(ctx, tor.running() ? "Stop" : missing ? "Install Tor" : "Start", x + w - 14 - (missing ? 176 : 146), y + 32, missing ? 70 : 40, 16, mouseX, mouseY, () -> {
            if (tor.running()) { tor.stop(); flash("Tor stopped"); }
            else if (missing) flash(TorRunner.openInstallGuide() ? "Opened prism/torinstall.txt - follow it, then come back" : "Could not open the guide");
            else flash(tor.start() ? "Starting Tor..." : tor.status());
        });
        button(ctx, "New identity", x + w - 14 - 102, y + 32, 70, 16, mouseX, mouseY, () -> flash(tor.newIdentity() ? "New Tor circuit requested" : tor.status()));
        button(ctx, "Use", x + w - 14 - 28, y + 32, 28, 16, mouseX, mouseY, () -> { pm.use(ProxyManager.TOR); flash("Next connections go through Tor"); });

        // add box
        float ix = x + 14, iy = y + 54, iw = w - 28 - 52;
        Glass.rounded(ctx, ix, iy, iw, 16, 4, inputFocused ? 0x30FFFFFF : 0x1EFFFFFF, 0x12FFFFFF);
        boolean blink = inputFocused && System.currentTimeMillis() / 500 % 2 == 0;
        String shown = input.isEmpty() && !inputFocused ? "name host:port [socks5|socks4|http] [user pass]" : input + (blink ? "_" : "");
        ctx.text(font, font.plainSubstrByWidth(shown, (int) iw - 10), (int) ix + 5, (int) iy + 4, input.isEmpty() ? FAINT : TEXT, false);
        hit(ix, iy, iw, 16, () -> inputFocused = true);
        button(ctx, "Add", x + w - 14 - 48, iy, 48, 16, mouseX, mouseY, this::add);

        // rows
        float ry = y + 78;
        for (int i = scroll; i < Math.min(list.size(), scroll + MAX_ROWS); i++) {
            ProxyManager.Proxy p = list.get(i);
            boolean current = p == active;
            boolean hover = mouseX >= x + 10 && mouseX < x + w - 10 && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover || current) Glass.rounded(ctx, x + 10, ry, w - 20, ROW_H - 2, 4, current ? ColorUtil.withAlpha(acc, 60) : 0x18FFFFFF, current ? ColorUtil.withAlpha(acc, 30) : 0x0CFFFFFF);
            ctx.text(font, p.name(), (int) x + 16, (int) ry + 5, current ? acc : TEXT, true);
            String detail = p.type().name().toLowerCase() + " " + p.address() + (p.user() != null ? " (auth)" : "");
            ctx.text(font, font.plainSubstrByWidth(detail, (int) w - 150), (int) x + 16 + font.width(p.name()) + 8, (int) ry + 5, FAINT, true);
            button(ctx, "Use", x + w - 14 - 36 - 20, ry + 1, 34, ROW_H - 4, mouseX, mouseY, () -> { pm.use(p.name()); flash("Next connections go through " + p.name()); });
            if (!p.isTor()) button(ctx, "×", x + w - 14 - 16, ry + 1, 16, ROW_H - 4, mouseX, mouseY, () -> { pm.remove(p.name()); flash("Removed " + p.name()); });
            ry += ROW_H;
        }

        // footer
        float gap = 6, btnW = (w - 28 - gap * 2) / 3, by = y + h - 24;
        button(ctx, "Direct", x + 14, by, btnW, 16, mouseX, mouseY, () -> { pm.use(null); flash("No proxy: connecting directly"); });
        button(ctx, checking ? "Checking..." : "Check IP", x + 14 + btnW + gap, by, btnW, 16, mouseX, mouseY, this::checkIp);
        button(ctx, "Done", x + 14 + 2 * (btnW + gap), by, btnW, 16, mouseX, mouseY, this::onClose);
        if (flash != null && System.currentTimeMillis() - flashAt < 6000) {
            String f = font.plainSubstrByWidth(flash, (int) w - 28);
            ctx.text(font, f, (int) (x + (w - font.width(f)) / 2), (int) by - 12, acc, true);
        }
    }

    private void button(GuiGraphicsExtractor ctx, String label, float x, float y, float w, float h, int mx, int my, Runnable action) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        Glass.rounded(ctx, x, y, w, h, 4, hover ? 0x44FFFFFF : 0x26FFFFFF, hover ? 0x30FFFFFF : 0x14FFFFFF);
        ctx.text(font, label, (int) (x + (w - font.width(label)) / 2), (int) (y + (h - 8) / 2 + 1), TEXT, true);
        hit(x, y, w, h, action);
    }

    private void hit(float x, float y, float w, float h, Runnable action) {
        rects.add(new float[]{x, y, w, h});
        actions.add(action);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        inputFocused = false;
        for (int i = rects.size() - 1; i >= 0; i--) {
            float[] r = rects.get(i);
            if (event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
                actions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= (int) Math.signum(vertical);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        if (!inputFocused) return super.keyPressed(event);
        switch (key) {
            case InputConstants.KEY_BACKSPACE -> { if (!input.isEmpty()) input = input.substring(0, input.length() - 1); }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> add();
            default -> { if (event.isPaste()) input += minecraft.keyboardHandler.getClipboard().trim(); }
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!inputFocused || !Character.isBmpCodePoint(event.codepoint())) return false;
        char chr = (char) event.codepoint();
        if (!Character.isISOControl(chr) && input.length() < 120) input += chr;
        return true;
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
