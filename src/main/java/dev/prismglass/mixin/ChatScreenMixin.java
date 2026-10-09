package dev.prismglass.mixin;

import dev.prismglass.gui.NowPlayingScreen;
import dev.prismglass.module.client.Hud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    /** With chat open the cursor is free: clicking the NowPlaying cover opens the song, the lyrics open the full sheet. */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void prism$nowPlayingClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0) return;
        float[] cover = Hud.npCover, text = Hud.npText;
        boolean onCover = cover != null && inside(cover, event.x(), event.y());
        boolean onText = text != null && inside(text, event.x(), event.y());
        if (!onCover && !onText) return;
        Minecraft.getInstance().setScreen(new NowPlayingScreen(onCover ? NowPlayingScreen.Page.SONG : NowPlayingScreen.Page.LYRICS));
        cir.setReturnValue(true);
    }

    private static boolean inside(float[] r, double x, double y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }
}
