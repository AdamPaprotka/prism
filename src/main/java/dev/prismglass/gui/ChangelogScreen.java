package dev.prismglass.gui;

import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.client.ClickGui;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/** The bundled CHANGELOG.md in a scrollable glass panel ("## " versions, "### " sections, "- " bullets). */
public class ChangelogScreen extends Screen {
    private static final int TEXT = 0xFFE6ECF5, DIM = 0xFFA8B3C4;
    private static @Nullable List<String> cached;

    private final @Nullable Screen parent;
    private float scroll, targetScroll;
    private int contentH;
    private float[] updateBtn;

    public ChangelogScreen(@Nullable Screen parent) {
        super(Component.literal("Changelog"));
        this.parent = parent;
    }

    /** CHANGELOG.md from the jar (empty if missing). */
    public static List<String> lines() {
        if (cached != null) return cached;
        List<String> out = new ArrayList<>();
        try (InputStream in = ChangelogScreen.class.getResourceAsStream("/assets/prismglass/CHANGELOG.md")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String l; (l = r.readLine()) != null; ) out.add(l);
            }
        } catch (Exception e) {
            Prism.LOG.error("[changelog] read failed", e);
        }
        return cached = out;
    }

    /** The newest version listed ("1.1.0"), or null. */
    public static @Nullable String latestVersion() {
        for (String l : lines()) {
            if (l.startsWith("## ")) return l.substring(3).split(" ")[0].trim();
        }
        return null;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ClickGui theme = Prism.modules().get(ClickGui.class);
        int accent = theme.accent.color();
        float w = Math.min(340, width - 32), h = height - 48, x = (width - w) / 2f, y = 24;
        Glass.panel(ctx, x, y, w, h, theme.panelStyle());
        ctx.text(font, "Changelog", (int) x + 12, (int) y + 9, TEXT, true);
        // update button in the header: checks GitHub, then downloads the new version
        String label = dev.prismglass.manager.Updater.label();
        float bw = font.width(label) + 14, bx = x + w - 10 - bw, by = y + 5;
        boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 14;
        boolean avail = dev.prismglass.manager.Updater.state == dev.prismglass.manager.Updater.State.AVAILABLE;
        Glass.rounded(ctx, bx, by, bw, 14, 4, avail ? (hover ? 0x66FFFFFF : 0x44FFFFFF) : (hover ? 0x44FFFFFF : 0x26FFFFFF), hover ? 0x30FFFFFF : 0x14FFFFFF);
        ctx.text(font, label, (int) (bx + 7), (int) by + 3, avail ? accent : TEXT, true);
        updateBtn = new float[]{bx, by, bw, 14};
        ctx.fill((int) x + 10, (int) y + 22, (int) (x + w - 10), (int) y + 23, 0x30FFFFFF);

        scroll += (targetScroll - scroll) * 0.35f;
        int top = (int) y + 26, bottom = (int) (y + h - 8);
        ctx.enableScissor((int) x, top, (int) (x + w), bottom);
        float cy = top + 4 - scroll;
        int textW = (int) w - 36;
        for (String raw : lines()) {
            if (raw.startsWith("# ")) continue; // the file title
            if (raw.startsWith("## ")) {
                cy += 6;
                ctx.text(font, raw.substring(3), (int) x + 12, (int) cy, accent, true);
                cy += 13;
            } else if (raw.startsWith("### ")) {
                cy += 2;
                ctx.text(font, raw.substring(4), (int) x + 14, (int) cy, TEXT, false);
                cy += 11;
            } else if (raw.startsWith("- ")) {
                List<FormattedCharSequence> wrapped = font.split(Component.literal(raw.substring(2).replace("`", "")), textW);
                ctx.text(font, "•", (int) x + 18, (int) cy, accent, false);
                for (FormattedCharSequence line : wrapped) {
                    ctx.text(font, line, (int) x + 26, (int) cy, DIM, false);
                    cy += 10;
                }
                cy += 1;
            } else if (!raw.isBlank()) {
                for (FormattedCharSequence line : font.split(Component.literal(raw), textW + 8)) {
                    ctx.text(font, line, (int) x + 14, (int) cy, DIM, false);
                    cy += 10;
                }
            }
        }
        ctx.disableScissor();
        contentH = (int) (cy + scroll - top);
        int view = bottom - top;
        float max = Math.max(0, contentH - view + 8);
        targetScroll = Math.max(0, Math.min(max, targetScroll));
        if (max > 0) {
            float barH = Math.max(16, view * view / (float) contentH), barY = top + (view - barH) * (scroll / max);
            ctx.fill((int) (x + w - 6), (int) barY, (int) (x + w - 4), (int) (barY + barH), 0x50FFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        float[] r = updateBtn;
        if (r != null && event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
            dev.prismglass.manager.Updater.click();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        targetScroll -= (float) vertical * 30;
        return true;
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
