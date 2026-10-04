package dev.prismglass.util;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * Keybind encoding. Keyboard keys use their GLFW code; mouse buttons are stored as
 * {@code MOUSE_OFFSET + button} so a single int covers both. -1 means unbound.
 */
public final class KeyUtil {
    public static final int NONE = -1;
    public static final int MOUSE_OFFSET = 1000;

    private KeyUtil() {}

    public static boolean isMouse(int key) { return key >= MOUSE_OFFSET; }

    public static String name(int key) {
        if (key == NONE) return "None";
        if (isMouse(key)) return "Mouse" + (key - MOUSE_OFFSET + 1);
        String name = GLFW.glfwGetKeyName(key, 0);
        if (name != null) return name.toUpperCase();
        String translation = InputConstants.Type.KEYSYM.getOrCreate(key).getName();
        String tail = translation.substring(translation.lastIndexOf('.') + 1);
        return tail.isEmpty() ? "Key" + key : Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
    }

    /** @return the key code, or Integer.MIN_VALUE when the name is unknown. */
    public static int fromName(String name) {
        String n = name.toLowerCase();
        if (n.equals("none") || n.equals("unbind")) return NONE;
        if (n.startsWith("mouse")) {
            try { return MOUSE_OFFSET + Integer.parseInt(n.substring(5)) - 1; } catch (NumberFormatException ignored) {}
        }
        InputConstants.Key key = InputConstants.getKey("key.keyboard." + n);
        if (key == InputConstants.UNKNOWN) return Integer.MIN_VALUE;
        return key.getValue();
    }
}
