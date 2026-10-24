package dev.prismglass.util;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;

/**
 * Keybind encoding. Keyboard keys use their SDL scancode (what 26.3's {@link InputConstants} and
 * {@code KeyEvent.key()} carry); mouse buttons are stored as {@code MOUSE_OFFSET + button} with the
 * SDL button number (1 left, 2 middle, 3 right, 4/5 side) so a single int covers both. -1 means unbound.
 * <p>
 * Up to 1.6.3 (26.1.2) codes were GLFW key codes and 0-based GLFW mouse buttons; {@link #fromLegacy}
 * translates those so configs saved by older versions keep meaning the same physical key.
 */
public final class KeyUtil {
    public static final int NONE = -1;
    public static final int MOUSE_OFFSET = 1000;

    /** SDL mouse buttons ({@code MouseButtonEvent.button()}, {@code MouseButtonInfo.button()}). */
    public static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
    public static final int MOUSE_MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;
    public static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;

    /** Key actions passed to {@code KeyboardHandler.keyPress} / {@code MouseHandler.onButton}. */
    public static final int PRESS = InputConstants.PRESS, RELEASE = InputConstants.RELEASE, REPEAT = InputConstants.REPEAT;

    private KeyUtil() {}

    public static boolean isMouse(int key) { return key >= MOUSE_OFFSET; }

    /** Buttons a module can be bound to: everything but left/right click (those stay vanilla). */
    public static boolean isBindableMouse(int button) { return button != MOUSE_LEFT && button != MOUSE_RIGHT; }

    /** The bind code for an SDL mouse button. */
    public static int mouse(int button) { return MOUSE_OFFSET + button; }

    /**
     * User-facing mouse numbering, kept from the GLFW days so "Mouse3" is still the wheel:
     * Mouse1 left, Mouse2 right, Mouse3 middle, Mouse4+ side buttons.
     */
    private static int mouseNumber(int sdlButton) {
        return switch (sdlButton) {
            case 1 -> 1;
            case 2 -> 3;
            case 3 -> 2;
            default -> sdlButton;
        };
    }

    private static int mouseFromNumber(int number) {
        return switch (number) {
            case 1 -> 1;
            case 2 -> 3;
            case 3 -> 2;
            default -> number;
        };
    }

    public static String name(int key) {
        if (key == NONE) return "None";
        if (isMouse(key)) return "Mouse" + mouseNumber(key - MOUSE_OFFSET);
        try {
            String name = SDLKeyboard.SDL_GetKeyName(SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false));
            if (name != null && name.codePointCount(0, name.length()) == 1) return name.toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {} // SDL not up yet (early config load)
        String translation = InputConstants.Type.KEYBOARD.getOrCreate(key).getName();
        String tail = translation.substring(translation.lastIndexOf('.') + 1);
        return tail.isEmpty() || translation.equals("key.keyboard." + key) ? "Key" + key : Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
    }

    /** Whether a bound key or mouse button is held right now. */
    public static boolean isDown(int key) {
        if (key == NONE) return false;
        if (isMouse(key)) return isMouseDown(key - MOUSE_OFFSET);
        return isKeyDown(key);
    }

    /** Whether a keyboard key (SDL scancode) is held. */
    public static boolean isKeyDown(int scancode) {
        if (scancode <= 0) return false;
        try { return InputConstants.isKeyDown(scancode); } catch (Throwable t) { return false; }
    }

    /** Whether an SDL mouse button is held (window-independent, like glfwGetMouseButton was). */
    public static boolean isMouseDown(int button) {
        if (button <= 0 || button > 32) return false;
        try { return (SDLMouse.nSDL_GetMouseState(0L, 0L) & (1 << (button - 1))) != 0; } catch (Throwable t) { return false; }
    }

    /**
     * GLFW-style button index (0 left, 1 right, 2 middle, 3+ side) for an SDL button. For code that keeps a
     * 0/1/2 "which button" convention internally; never store it as a bind.
     */
    public static int legacyButton(int sdlButton) {
        return switch (sdlButton) {
            case 1 -> 0;
            case 2 -> 2;
            case 3 -> 1;
            default -> sdlButton - 1;
        };
    }

    // ---- cursor (GLFW_CURSOR input modes are gone; SDL3 has relative mode + cursor visibility) ----

    /** Cursor locked to the window and hidden, raw motion only (old GLFW_CURSOR_DISABLED). */
    public static boolean cursorLocked(com.mojang.blaze3d.platform.Window window) {
        return SDLMouse.SDL_GetWindowRelativeMouseMode(window.handle());
    }

    public static void setCursorLocked(com.mojang.blaze3d.platform.Window window, boolean locked) {
        if (cursorLocked(window) != locked) SDLMouse.SDL_SetWindowRelativeMouseMode(window.handle(), locked);
    }

    /** Cursor invisible but free (old GLFW_CURSOR_HIDDEN). */
    public static boolean cursorHidden() { return !SDLMouse.SDL_CursorVisible(); }

    public static void setCursorHidden(boolean hidden) {
        if (cursorHidden() == hidden) return;
        if (hidden) SDLMouse.SDL_HideCursor(); else SDLMouse.SDL_ShowCursor();
    }

    /** Free, visible cursor (old GLFW_CURSOR_NORMAL). */
    public static void setCursorNormal(com.mojang.blaze3d.platform.Window window) {
        setCursorLocked(window, false);
        setCursorHidden(false);
    }

    /** Move the cursor, in window (screen) coordinates (old glfwSetCursorPos). */
    public static void warpCursor(com.mojang.blaze3d.platform.Window window, double x, double y) {
        SDLMouse.SDL_WarpMouseInWindow(window.handle(), (float) x, (float) y);
    }

    /** Either shift key held. */
    public static boolean shiftDown() { return isKeyDown(InputConstants.KEY_LSHIFT) || isKeyDown(InputConstants.KEY_RSHIFT); }

    /** Either control key held. */
    public static boolean controlDown() { return isKeyDown(InputConstants.KEY_LCONTROL) || isKeyDown(InputConstants.KEY_RCONTROL); }

    /** @return the key code, or Integer.MIN_VALUE when the name is unknown. */
    public static int fromName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("none") || n.equals("unbind")) return NONE;
        if (n.startsWith("mouse")) {
            try { return MOUSE_OFFSET + mouseFromNumber(Integer.parseInt(n.substring(5))); } catch (NumberFormatException ignored) {}
        }
        try {
            InputConstants.Key key = InputConstants.getKey("key.keyboard." + n);
            if (key == InputConstants.UNKNOWN || key.getType() != InputConstants.Type.KEYBOARD) return Integer.MIN_VALUE;
            return key.getValue();
        } catch (IllegalArgumentException e) { // NumberFormatException for names that aren't keys
            return Integer.MIN_VALUE;
        }
    }

    /** Config form of a bind: the vanilla key name, stable across key-code changes. */
    public static String saveName(int key) {
        if (key == NONE) return "none";
        if (isMouse(key)) return InputConstants.Type.MOUSE.getOrCreate(key - MOUSE_OFFSET).getName();
        return InputConstants.Type.KEYBOARD.getOrCreate(key).getName();
    }

    /** @return the bind for a {@link #saveName} string, or Integer.MIN_VALUE when it isn't one. */
    public static int fromSaveName(String name) {
        if (name.equals("none")) return NONE;
        try {
            InputConstants.Key key = InputConstants.getKey(name);
            if (key == InputConstants.UNKNOWN) return NONE;
            return key.getType() == InputConstants.Type.MOUSE ? MOUSE_OFFSET + key.getValue() : key.getValue();
        } catch (IllegalArgumentException e) {
            return Integer.MIN_VALUE;
        }
    }

    /** A bind code saved by 1.6.3 or older (GLFW key code / 0-based GLFW mouse button) in today's encoding. */
    public static int fromLegacy(int code) {
        if (code == NONE) return NONE;
        if (code >= MOUSE_OFFSET) {
            int glfw = code - MOUSE_OFFSET;
            return MOUSE_OFFSET + switch (glfw) {
                case 0 -> MOUSE_LEFT;
                case 1 -> MOUSE_RIGHT;
                case 2 -> MOUSE_MIDDLE;
                default -> glfw + 1;
            };
        }
        int sdl = GLFW_TO_SDL.getOrDefault(code, -1);
        return sdl < 0 ? NONE : sdl;
    }

    /** GLFW key code -> SDL scancode, matched through the vanilla key names both versions share. */
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap GLFW_TO_SDL = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    static {
        int[] pairs = {
            32, 44, 39, 52, 44, 54, 45, 45, 46, 55, 47, 56, 48, 39, 49, 30, 50, 31, 51, 32, 52, 33, 53, 34, 54, 35,
            55, 36, 56, 37, 57, 38, 59, 51, 61, 46, 65, 4, 66, 5, 67, 6, 68, 7, 69, 8, 70, 9, 71, 10, 72, 11, 73, 12,
            74, 13, 75, 14, 76, 15, 77, 16, 78, 17, 79, 18, 80, 19, 81, 20, 82, 21, 83, 22, 84, 23, 85, 24, 86, 25,
            87, 26, 88, 27, 89, 28, 90, 29, 91, 47, 92, 49, 93, 48, 96, 53, 161, 100, 162, 50, 256, 41, 257, 40,
            258, 43, 259, 42, 260, 73, 261, 76, 262, 79, 263, 80, 264, 81, 265, 82, 266, 75, 267, 78, 268, 74,
            269, 77, 280, 57, 281, 71, 282, 83, 283, 70, 284, 72, 290, 58, 291, 59, 292, 60, 293, 61, 294, 62,
            295, 63, 296, 64, 297, 65, 298, 66, 299, 67, 300, 68, 301, 69, 302, 104, 303, 105, 304, 106, 305, 107,
            306, 108, 307, 109, 308, 110, 309, 111, 310, 112, 311, 113, 312, 114, 313, 115, 320, 98, 321, 89,
            322, 90, 323, 91, 324, 92, 325, 93, 326, 94, 327, 95, 328, 96, 329, 97,
            330, 99,  // GLFW_KEY_KP_DECIMAL is the numpad '.', SDL_SCANCODE_KP_PERIOD
            331, 84, 332, 85, 333, 86, 334, 87, 335, 88, 336, 103, 340, 225, 341, 224, 342, 226, 343, 227,
            344, 229, 345, 228, 346, 230, 347, 231,
            348, 101  // GLFW_KEY_MENU is the context-menu key, SDL_SCANCODE_APPLICATION
        };
        for (int i = 0; i < pairs.length; i += 2) GLFW_TO_SDL.put(pairs[i], pairs[i + 1]);
    }
}
