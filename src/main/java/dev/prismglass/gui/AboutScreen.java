package dev.prismglass.gui;

import dev.prismglass.BuildInfo;
import dev.prismglass.Prism;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.gui.render.PrismLogo;
import dev.prismglass.module.Module;
import dev.prismglass.module.client.ClickGui;
import dev.prismglass.util.ColorUtil;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Clicking the Prism logo: version + channel (release/beta/indev) and the build/runtime details for bug reports. */
public class AboutScreen extends Screen {
    private static final int TEXT = 0xFFF2F6FC, DIM = 0xFFD5DEEA, FAINT = 0xFF9FAEC2;

    private final @Nullable Screen parent;
    private final List<float[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();
    private String flash;
    private long flashAt;
    private final long openedAt = System.currentTimeMillis();

    public AboutScreen(@Nullable Screen parent) {
        super(Component.literal("About Prism"));
        this.parent = parent;
    }

    @Override public boolean isPauseScreen() { return false; }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private static String uptime() {
        long s = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return s >= 3600 ? String.format("%dh %02dm", s / 3600, s / 60 % 60) : String.format("%dm %02ds", s / 60, s % 60);
    }

    /** Label/value rows. Nothing personal (no name, server or paths) so "Copy info" is safe to paste anywhere. */
    private List<String[]> rows() {
        List<String[]> r = new ArrayList<>();
        r.add(new String[]{"Build", "#" + BuildInfo.build() + "  ·  " + BuildInfo.time()});
        r.add(new String[]{"Minecraft", modVersion("minecraft") + "  ·  Loader " + modVersion("fabricloader") + "  ·  API " + modVersion("fabric-api").replace("+" + modVersion("minecraft"), "")});
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
        r.add(new String[]{"Java", System.getProperty("java.version") + "  ·  " + used + " / " + max + " MB"});
        String gpu;
        try { gpu = com.mojang.blaze3d.systems.RenderSystem.getDevice().getRenderer(); } catch (Throwable t) { gpu = "?"; }
        r.add(new String[]{"GPU", gpu});
        r.add(new String[]{"OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"});
        int on = 0;
        for (Module m : Prism.modules().all()) if (m.isEnabled()) on++;
        r.add(new String[]{"Modules", Prism.modules().all().size() + " total  ·  " + on + " on"});
        r.add(new String[]{"Mods", FabricLoader.getInstance().getAllMods().size() + " loaded"});
        r.add(new String[]{"AntiCheat", Prism.anticheat().preset.get() + " preset"});
        r.add(new String[]{"Profile", Prism.config().getActive()});
        boolean sp = Prism.modules().get(dev.prismglass.module.client.StreamProof.class).isEnabled();
        r.add(new String[]{"StreamProof", sp ? "on" : "off"});
        r.add(new String[]{"Session", uptime() + "  ·  " + minecraft.getFps() + " fps" + (minecraft.isLocalServer() ? "  ·  singleplayer" : minecraft.level != null ? "  ·  multiplayer" : "")});
        return r;
    }

    private static int channelColor(String channel) {
        return switch (channel) {
            case "release" -> 0xFF4ADE80;
            case "beta" -> 0xFFFACC15;
            default -> 0xFFF472B6; // indev
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        dev.prismglass.streamproof.Overlay.markGui();
        ClickGui theme = Prism.modules().get(ClickGui.class);
        float in = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        List<String[]> rows = rows();
        float w = Math.min(320, width - 32), h = 62 + rows.size() * 12 + 34;
        float x = (width - w) / 2f, y = (height - h) / 2f + (1 - in) * 10;
        Glass.panel(ctx, x, y, w, h, theme.panelStyle());

        // header: logo, name, version and the channel badge
        PrismLogo.draw(ctx, x + 24, y + 26, 20, theme.logoSpeed.getFloat(), 1f);
        ctx.text(font, Prism.NAME, (int) x + 48, (int) y + 12, TEXT, true);
        String version = Prism.VERSION;
        ctx.text(font, version, (int) x + 48, (int) y + 26, DIM, true);
        String channel = BuildInfo.channel();
        int cc = channelColor(channel);
        float bx = x + 48 + font.width(version) + 6, bw = font.width(channel) + 10;
        Glass.pill(ctx, bx, y + 24, bw, 11, ColorUtil.withAlpha(cc, 90), ColorUtil.withAlpha(cc, 60), 0.6f);
        ctx.text(font, channel, (int) (bx + 5), (int) y + 26, cc, false);
        String sub = FabricLoader.getInstance().isDevelopmentEnvironment() ? "development environment" : "jar build";
        ctx.text(font, sub, (int) x + 48, (int) y + 39, FAINT, true);
        ctx.fill((int) x + 12, (int) y + 54, (int) (x + w - 12), (int) y + 55, 0x30FFFFFF);

        // details
        float ry = y + 62;
        for (String[] row : rows) {
            ctx.text(font, row[0], (int) x + 14, (int) ry, FAINT, true);
            String value = font.plainSubstrByWidth(row[1], (int) w - 96);
            ctx.text(font, value, (int) x + 82, (int) ry, DIM, true);
            ry += 12;
        }

        // buttons
        buttonRects.clear();
        buttonActions.clear();
        String[] labels = {"Changelog", "Copy info", "Open folder", dev.prismglass.manager.Updater.label()};
        Runnable[] actions = {
            () -> minecraft.setScreen(new ChangelogScreen(this)),
            () -> { minecraft.keyboardHandler.setClipboard(report()); flash = "Copied"; flashAt = System.currentTimeMillis(); },
            () -> net.minecraft.util.Util.getPlatform().openPath(Prism.config().folder()),
            dev.prismglass.manager.Updater::click,
        };
        float gap = 6, btnW = (w - 28 - gap * 3) / 4, btnY = y + h - 26;
        for (int i = 0; i < labels.length; i++) {
            float btnX = x + 14 + i * (btnW + gap);
            boolean hover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + 16;
            Glass.rounded(ctx, btnX, btnY, btnW, 16, 4, hover ? 0x44FFFFFF : 0x26FFFFFF, hover ? 0x30FFFFFF : 0x14FFFFFF);
            String label = i == 1 && flash != null && System.currentTimeMillis() - flashAt < 1500 ? flash : labels[i];
            ctx.text(font, label, (int) (btnX + (btnW - font.width(label)) / 2), (int) btnY + 4, TEXT, true);
            buttonRects.add(new float[]{btnX, btnY, btnW, 16});
            buttonActions.add(actions[i]);
        }
    }

    /** Plain-text version of the screen for bug reports. */
    private String report() {
        StringBuilder b = new StringBuilder("Prism Glass ").append(BuildInfo.full()).append('\n');
        for (String[] row : rows()) b.append(row[0]).append(": ").append(row[1].replace("  ·  ", ", ")).append('\n');
        return b.toString();
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
    public void onClose() { minecraft.setScreen(parent); }
}
