package dev.prismglass.gui;

import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.manager.NowPlaying;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.module.client.Hud;
import dev.prismglass.util.ColorUtil;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * Opened by clicking the NowPlaying box in chat. SONG: big cover, title, artists, album, time and a Spotify link (a
 * search for the exact song: Windows' media session doesn't give Spotify's track id). LYRICS: the whole sheet top to
 * bottom with timestamps and translations, the line being sung highlighted and followed until you scroll.
 */
public class NowPlayingScreen extends Screen {
    public enum Page { SONG, LYRICS }

    private static final int TEXT = 0xFFE6ECF5, DIM = 0xFFA8B3C4;
    private Page page;
    private float scroll, targetScroll;
    private boolean follow = true;
    private final List<float[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();
    private String flash;
    private long flashAt;

    public NowPlayingScreen(Page page) {
        super(Component.literal("Now playing"));
        this.page = page;
    }

    @Override public boolean isPauseScreen() { return false; }

    private static String time(long ms) { return String.format("%d:%02d", ms / 60000, ms / 1000 % 60); }

    private static String searchQuery() { return NowPlaying.title + " " + NowPlaying.artist; }

    private static String webLink() {
        return "https://open.spotify.com/search/" + URLEncoder.encode(searchQuery(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ClickGui theme = Prism.modules().get(ClickGui.class);
        int accent = theme.accent.color();
        float w = Math.min(page == Page.SONG ? 300 : 360, width - 32), h = page == Page.SONG ? Math.min(176, height - 48) : height - 48;
        float x = (width - w) / 2f, y = page == Page.SONG ? (height - h) / 2f : 24;
        Glass.panel(ctx, x, y, w, h, theme.panelStyle());
        buttonRects.clear();
        buttonActions.clear();
        if (!NowPlaying.active()) {
            ctx.text(font, "Nothing is playing", (int) x + 12, (int) y + 12, DIM, false);
            return;
        }
        if (page == Page.SONG) song(ctx, x, y, w, h, mouseX, mouseY, accent);
        else lyrics(ctx, x, y, w, h, mouseX, mouseY, accent);
    }

    private void button(GuiGraphicsExtractor ctx, String label, float bx, float by, float bw, int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 16;
        Glass.rounded(ctx, bx, by, bw, 16, 4, hover ? 0x44FFFFFF : 0x26FFFFFF, hover ? 0x30FFFFFF : 0x14FFFFFF);
        ctx.text(font, label, (int) (bx + (bw - font.width(label)) / 2), (int) by + 4, TEXT, true);
        buttonRects.add(new float[]{bx, by, bw, 16});
        buttonActions.add(action);
    }

    private void song(GuiGraphicsExtractor ctx, float x, float y, float w, float h, int mouseX, int mouseY, int accent) {
        int art = 96, ax = (int) x + 12, ay = (int) y + 12;
        if (NowPlaying.hasCover()) {
            ctx.blit(RenderPipelines.GUI_TEXTURED, NowPlaying.COVER, ax, ay, 0, 0, art, art, NowPlaying.coverW, NowPlaying.coverH, NowPlaying.coverW, NowPlaying.coverH);
        } else {
            Glass.rounded(ctx, ax, ay, art, art, 6, 0x60303848, 0x60181C28);
        }
        int tx = ax + art + 12, tw = (int) (x + w - 12) - tx, ty = ay + 2;
        boolean ad = NowPlaying.ad;
        for (FormattedCharSequence row : font.split(Component.literal(ad ? "Ad break" : NowPlaying.title), tw)) {
            ctx.text(font, row, tx, ty, TEXT, true);
            ty += 10;
        }
        ty += 3;
        // Windows gives all the artists as one string ("A, B" or "A feat. B")
        for (FormattedCharSequence row : font.split(Component.literal(NowPlaying.artist.isEmpty() ? "Unknown artist" : NowPlaying.artist), tw)) {
            ctx.text(font, row, tx, ty, accent, false);
            ty += 10;
        }
        if (!NowPlaying.album.isEmpty()) {
            ty += 3;
            for (FormattedCharSequence row : font.split(Component.literal(NowPlaying.album), tw)) {
                ctx.text(font, row, tx, ty, DIM, false);
                ty += 10;
            }
        }
        // time + progress under the cover
        long pos = NowPlaying.position(), dur = NowPlaying.duration();
        int by = ay + art + 10;
        if (dur > 0) {
            String t = time(pos) + " / " + time(dur) + (NowPlaying.playing() ? "" : "  paused");
            ctx.text(font, t, ax, by, DIM, false);
            int barX = ax + font.width(t) + 8, barW = (int) (x + w - 12) - barX;
            ctx.fill(barX, by + 3, barX + barW, by + 4, 0x40FFFFFF);
            ctx.fill(barX, by + 3, barX + (int) (barW * Mth.clamp((float) pos / dur, 0, 1)), by + 4, accent);
        }
        ctx.text(font, font.plainSubstrByWidth(webLink().replace("https://", ""), (int) w - 24), ax, by + 14, 0xFF7FB8FF, false);
        float bw = (w - 24 - 12) / 3, bY = y + h - 26;
        button(ctx, "Open in Spotify", x + 12, bY, bw, mouseX, mouseY,
            () -> net.minecraft.util.Util.getPlatform().openUri(URI.create("spotify:search:" + URLEncoder.encode(searchQuery(), StandardCharsets.UTF_8).replace("+", "%20"))));
        button(ctx, flash != null && System.currentTimeMillis() - flashAt < 1500 ? flash : "Copy link", x + 18 + bw, bY, bw, mouseX, mouseY, () -> {
            minecraft.keyboardHandler.setClipboard(webLink());
            flash = "Copied";
            flashAt = System.currentTimeMillis();
        });
        button(ctx, "Lyrics", x + 24 + bw * 2, bY, bw, mouseX, mouseY, () -> { page = Page.LYRICS; follow = true; scroll = targetScroll = 0; });
    }

    private void lyrics(GuiGraphicsExtractor ctx, float x, float y, float w, float h, int mouseX, int mouseY, int accent) {
        String head = NowPlaying.title + "  ·  " + NowPlaying.artist;
        ctx.text(font, font.plainSubstrByWidth(head, (int) w - 90), (int) x + 12, (int) y + 9, TEXT, true);
        button(ctx, "Song", x + w - 62, y + 5, 52, mouseX, mouseY, () -> page = Page.SONG);
        ctx.fill((int) x + 10, (int) y + 24, (int) (x + w - 10), (int) y + 25, 0x30FFFFFF);

        List<NowPlaying.Line> lyr = NowPlaying.lyrics;
        Hud hud = Prism.modules().get(Hud.class);
        List<String> tr = hud.lyricsTranslate.is("Off") ? List.of() : NowPlaying.translated;
        int top = (int) y + 28, bottom = (int) (y + h - 8);
        if (lyr.isEmpty()) {
            ctx.text(font, NowPlaying.ad ? "Ad break" : NowPlaying.lyricsLoading ? "Looking for lyrics..." : "No synced lyrics for this song", (int) x + 12, top + 4, DIM, false);
            return;
        }
        int cur = NowPlaying.lineAt(NowPlaying.position());
        int textW = (int) w - 60;
        scroll += (targetScroll - scroll) * 0.25f;
        ctx.enableScissor((int) x, top, (int) (x + w), bottom);
        float cy = top + 4 - scroll, curY = -1;
        for (int i = 0; i < lyr.size(); i++) {
            NowPlaying.Line line = lyr.get(i);
            boolean now = i == cur;
            if (now) curY = cy + scroll - top;
            ctx.text(font, time(line.time()), (int) x + 12, (int) cy, now ? accent : 0xFF6A7280, false);
            if (line.text().isEmpty()) { cy += 10; continue; }
            int col = now ? accent : i < cur ? DIM : TEXT;
            List<FormattedCharSequence> rows = font.split(Component.literal(line.text()), textW);
            for (int j = 0; j < rows.size(); j++) {
                ctx.text(font, rows.get(j), (int) x + 46, (int) cy, col, now);
                if (now && j == rows.size() - 1) ctx.text(font, " <-", (int) x + 46 + font.width(rows.get(j)), (int) cy, accent, true);
                cy += 10;
            }
            String t = i < tr.size() ? tr.get(i) : "";
            if (!t.isEmpty() && !t.equalsIgnoreCase(line.text())) {
                for (FormattedCharSequence row : font.split(Component.literal(t), textW)) {
                    ctx.text(font, row, (int) x + 46, (int) cy, ColorUtil.fade(col, 0.6f), false);
                    cy += 10;
                }
            }
            cy += 3;
        }
        ctx.disableScissor();
        int contentH = (int) (cy + scroll - top), view = bottom - top;
        float max = Math.max(0, contentH - view + 8);
        // keep the line being sung a third of the way down until you scroll yourself
        if (follow && curY >= 0) targetScroll = curY - view / 3f;
        targetScroll = Mth.clamp(targetScroll, 0, max);
        if (max > 0) {
            float barH = Math.max(16, view * view / (float) contentH), barY = top + (view - barH) * (scroll / max);
            ctx.fill((int) (x + w - 6), (int) barY, (int) (x + w - 4), (int) (barY + barH), 0x50FFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = 0; i < buttonRects.size(); i++) {
            float[] r = buttonRects.get(i);
            if (event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
                buttonActions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        follow = false;
        targetScroll -= (float) vertical * 30;
        return true;
    }
}
