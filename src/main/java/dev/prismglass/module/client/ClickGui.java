package dev.prismglass.module.client;

import dev.prismglass.gui.ClickGuiScreen;
import dev.prismglass.gui.render.Glass;
import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.BoolSetting;
import dev.prismglass.setting.ColorSetting;
import dev.prismglass.setting.ModeSetting;
import dev.prismglass.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

/** Theme + opener for the ClickGUI. Every visual of the GUI and HUD is driven from here. */
public class ClickGui extends Module {
    public final ModeSetting style = mode("Style", "Liquid", "Liquid = real refracting glass (shader). Frosted = translucent panels. Flat = Meteor-like.", "Liquid", "Frosted", "Flat");
    public final ColorSetting accent = color("Accent", 0xFF8AB4FF, "Accent colour (enabled modules, sliders).");
    public final ColorSetting tint = color("Tint", 0xFF9FB8D8, "Frosted glass tint colour.");
    public final ColorSetting liquidTint = color("LiquidTint", 0xFF101622, "Liquid glass tint (dark keeps text readable).");
    public final NumberSetting tintStrength = num("TintStrength", 0.30, 0, 1, 0.01, "How strongly the liquid tint covers the scene.");
    public final NumberSetting refraction = num("Refraction", 14, 0, 30, 0.5, "Lens strength at the glass edge.");
    public final NumberSetting dispersion = num("Dispersion", 1.4, 0, 4, 0.1, "Rainbow fringe at the edge.");
    public final NumberSetting glassBlur = num("GlassBlur", 1.0, 0, 6, 0.25, "Blur through the glass (liquid glass is mostly clear).");
    public final NumberSetting opacity = num("Opacity", 0.30, 0.05, 1.0, 0.01, "Frosted glass body opacity.");
    public final NumberSetting radius = num("Radius", 8, 0, 14, 0.5, "Corner radius.");
    public final NumberSetting rim = num("Rim", 1.0, 0, 2, 0.05, "Specular edge strength.");
    public final BoolSetting sheen = bool("Sheen", true, "Glare highlight across the top of panels.");
    public final BoolSetting shadow = bool("Shadow", true, "Soft drop shadows.");
    public final BoolSetting blur = bool("Blur", false, "Blur the whole world behind the GUI (off suits Liquid).");
    public final BoolSetting logo = bool("Logo", true, "Spinning prism in the GUI.");
    public final NumberSetting logoSpeed = num("LogoSpeed", 0.3, 0, 2, 0.05, "Prism revolutions per second.");
    public final NumberSetting animSpeed = num("AnimSpeed", 1.0, 0.2, 3, 0.1, "Animation speed.");
    public final NumberSetting panelWidth = num("PanelWidth", 116, 90, 180, 1, "Width of category panels.");
    public final BoolSetting walk = bool("WalkInGui", true, "Keep moving (WASD/jump/sneak/sprint) while the ClickGUI is open.");
    public final BoolSetting descriptions = bool("Descriptions", true, "Show hovered module/setting descriptions.");
    public final BoolSetting blocky = bool("Blocky", false, "Fun: pixel-art GUI. Same layout and glass, built from blocks; text stays sharp.");
    public final NumberSetting blockSize = num("BlockSize", 2, 1, 4, 0.5, "Size of one Blocky pixel (GUI units).");
    /** Newest changelog version already announced (hidden). */
    public final dev.prismglass.setting.TextSetting seenVersion = text("SeenVersion", "", "Last changelog version shown.").visibleWhen(() -> false);

    public ClickGui() {
        super("ClickGui", "Opens the module interface.", Category.CLIENT);
        bind.set(GLFW.GLFW_KEY_RIGHT_SHIFT);
        drawn.set(false);
    }

    @Override
    public void onEnable() {
        // Open on the next frame: opening right now would let the same key press reach the screen
        // (and close it), and the chat screen closes itself after running a ".t clickgui" command.
        mc.schedule(() -> mc.setScreen(new ClickGuiScreen()));
        setEnabledSilently(false);
    }

    public Glass.Style panelStyle() {
        if (style.is("Liquid")) {
            return new Glass.Style(liquidTint.color(), 1f, radius.getFloat(), shadow.get(), false, rim.getFloat(),
                true, refraction.getFloat(), dispersion.getFloat(), glassBlur.getFloat(), tintStrength.getFloat());
        }
        boolean glass = style.is("Frosted");
        return new Glass.Style(glass ? tint.color() : 0xFF15171C, glass ? opacity.getFloat() : 0.92f,
            radius.getFloat(), shadow.get(), glass && sheen.get(), glass ? rim.getFloat() : 0.25f);
    }
}
