package dev.prismglass.gui;

import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.manager.AccountManager;
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
import org.lwjgl.glfw.GLFW;

/**
 * Account manager (ClickGUI "Accounts" or {@code .account}): cracked names you can log in with for offline-mode
 * servers, plus the account the game started with. Type a name and press Enter or Add; Login swaps the session for
 * the next server you join.
 */
public class AccountsScreen extends Screen {
    private static final int TEXT = 0xFFF2F6FC, DIM = 0xFFD5DEEA, FAINT = 0xFF9FAEC2;
    private static final int ROW_H = 18, MAX_ROWS = 8;

    private final @Nullable Screen parent;
    private final List<float[]> rects = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private final long openedAt = System.currentTimeMillis();
    private String input = "";
    private boolean inputFocused = true;
    private int scroll;
    private String flash;
    private long flashAt;

    public AccountsScreen(@Nullable Screen parent) {
        super(Component.literal("Accounts"));
        this.parent = parent;
    }

    @Override public boolean isPauseScreen() { return false; }

    private void flash(String s) { flash = s; flashAt = System.currentTimeMillis(); }

    private void add() {
        String name = input.trim();
        if (name.isEmpty()) return;
        if (Prism.accounts().add(name)) { input = ""; flash("Added " + name); }
        else flash("Invalid or already saved");
    }

    private void login(String name) {
        if (Prism.accounts().login(name)) flash("Logged in as " + name + (minecraft.level != null ? " (applies on next join)" : ""));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        dev.prismglass.streamproof.Overlay.markGui();
        rects.clear();
        actions.clear();
        ClickGui theme = Prism.modules().get(ClickGui.class);
        AccountManager am = Prism.accounts();
        List<String> list = am.all();
        int acc = theme.accent.color();
        float in = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        int rows = Math.max(1, Math.min(MAX_ROWS, list.size()));
        scroll = Math.max(0, Math.min(scroll, list.size() - MAX_ROWS));
        float w = Math.min(320, width - 32), h = 66 + rows * ROW_H + 46;
        float x = (width - w) / 2f, y = (height - h) / 2f + (1 - in) * 10;
        Glass.panel(ctx, x, y, w, h, theme.panelStyle());

        // header: title, who you are right now
        ctx.text(font, "Accounts", (int) x + 14, (int) y + 12, TEXT, true);
        String who = am.currentName();
        String badge = am.isOriginal() ? "original" : "cracked";
        int bc = am.isOriginal() ? 0xFF4ADE80 : acc;
        float bw = font.width(badge) + 10, bx = x + w - 14 - bw;
        Glass.pill(ctx, bx, y + 10, bw, 11, ColorUtil.withAlpha(bc, 90), ColorUtil.withAlpha(bc, 60), 0.6f);
        ctx.text(font, badge, (int) (bx + 5), (int) y + 12, bc, false);
        ctx.text(font, who, (int) (bx - 6 - font.width(who)), (int) y + 12, DIM, true);
        ctx.fill((int) x + 12, (int) y + 27, (int) (x + w - 12), (int) y + 28, 0x30FFFFFF);

        // new name box + Add
        float ix = x + 14, iy = y + 34, iw = w - 28 - 52;
        Glass.rounded(ctx, ix, iy, iw, 16, 4, inputFocused ? 0x30FFFFFF : 0x1EFFFFFF, 0x12FFFFFF);
        boolean blink = inputFocused && System.currentTimeMillis() / 500 % 2 == 0;
        String shown = input.isEmpty() && !inputFocused ? "name..." : input + (blink ? "_" : "");
        ctx.text(font, shown, (int) ix + 5, (int) iy + 4, input.isEmpty() ? FAINT : TEXT, false);
        hit(ix, iy, iw, 16, () -> inputFocused = true);
        button(ctx, "Add", x + w - 14 - 48, iy, 48, 16, mouseX, mouseY, this::add);

        // rows
        float ry = y + 58;
        if (list.isEmpty()) ctx.text(font, "No cracked accounts yet. Type a name above.", (int) x + 14, (int) ry + 5, FAINT, true);
        for (int i = scroll; i < Math.min(list.size(), scroll + MAX_ROWS); i++) {
            String name = list.get(i);
            boolean current = !am.isOriginal() && name.equalsIgnoreCase(who);
            boolean hover = mouseX >= x + 10 && mouseX < x + w - 10 && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover || current) Glass.rounded(ctx, x + 10, ry, w - 20, ROW_H - 2, 4, current ? ColorUtil.withAlpha(acc, 60) : 0x18FFFFFF, current ? ColorUtil.withAlpha(acc, 30) : 0x0CFFFFFF);
            ctx.text(font, name, (int) x + 16, (int) ry + 5, current ? acc : TEXT, true);
            button(ctx, "Login", x + w - 14 - 44 - 20, ry + 1, 42, ROW_H - 4, mouseX, mouseY, () -> login(name));
            button(ctx, "×", x + w - 14 - 16, ry + 1, 16, ROW_H - 4, mouseX, mouseY, () -> { am.remove(name); flash("Removed " + name); });
            ry += ROW_H;
        }
        if (list.size() > MAX_ROWS) ctx.text(font, (scroll + 1) + "-" + Math.min(list.size(), scroll + MAX_ROWS) + " of " + list.size(), (int) x + 14, (int) ry + 2, FAINT, false);

        // footer
        float gap = 6, btnW = (w - 28 - gap * 3) / 4, by = y + h - 24;
        button(ctx, "Original", x + 14, by, btnW, 16, mouseX, mouseY, () -> { am.loginOriginal(); flash("Back to " + am.originalName()); });
        button(ctx, "Random", x + 14 + btnW + gap, by, btnW, 16, mouseX, mouseY, () -> { input = AccountManager.randomName(); inputFocused = true; });
        var px = Prism.proxies().active();
        button(ctx, px == null ? "Proxies" : "Proxy: " + px.name(), x + 14 + 2 * (btnW + gap), by, btnW, 16, mouseX, mouseY, () -> minecraft.setScreen(new ProxiesScreen(this)));
        button(ctx, "Done", x + 14 + 3 * (btnW + gap), by, btnW, 16, mouseX, mouseY, this::onClose);
        if (flash != null && System.currentTimeMillis() - flashAt < 2500) {
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
        for (int i = rects.size() - 1; i >= 0; i--) { // later hits are drawn on top
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
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (!inputFocused) return super.keyPressed(event);
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> { if (!input.isEmpty()) input = input.substring(0, input.length() - 1); }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> add();
            default -> {}
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!inputFocused || !Character.isBmpCodePoint(event.codepoint())) return false;
        char chr = (char) event.codepoint();
        if ((Character.isLetterOrDigit(chr) || chr == '_') && chr < 128 && input.length() < 16) input += chr;
        return true;
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
