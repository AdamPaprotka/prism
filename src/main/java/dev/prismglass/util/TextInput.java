package dev.prismglass.util;

import net.minecraft.client.Minecraft;

/**
 * 26.3 runs on SDL3, which only delivers typed characters (charTyped) while text input is switched on. Vanilla's
 * EditBox switches it on when it gains focus; Prism's own fields aren't EditBoxes, so their screens call
 * {@link #sync} every frame with whether one of their fields is being typed into.
 */
public final class TextInput {
    private TextInput() {}

    /** On while {@code typing}, off again when this owner stops (never turns off another owner's input). */
    public static void sync(Object owner, boolean typing) {
        var input = Minecraft.getInstance().textInputManager();
        if (typing) input.startTextInput(owner);
        else input.stopTextInput(owner);
    }
}
