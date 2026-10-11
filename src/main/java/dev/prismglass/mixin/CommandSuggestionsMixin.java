package dev.prismglass.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.prismglass.Prism;
import dev.prismglass.command.CommandManager;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tab completion for Prism's chat commands: typing "{prefix}tog" pops up the same suggestion list vanilla shows for
 * /commands (Tab / arrows / click to pick), and once the command is known its usage shows above the box.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    @Shadow @Final private EditBox input;
    @Shadow @Final private List<FormattedCharSequence> commandUsage;
    @Shadow private int commandUsagePosition;
    @Shadow private ParseResults<ClientSuggestionProvider> currentParse;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow private CommandSuggestions.SuggestionsList suggestions;
    @Shadow private boolean currentParseIsCommand;
    @Shadow private boolean currentParseIsMessage;
    @Shadow private boolean allowSuggestions;
    @Shadow private boolean keepSuggestions;

    @Shadow protected abstract void recomputeUsageBoxWidth();
    @Shadow public abstract void showSuggestions(boolean immediateNarration);

    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void prism$commandCompletion(CallbackInfo ci) {
        if (Prism.commands() == null) return;
        String prefix = Prism.commands().getPrefix();
        String value = input.getValue();
        // "/" belongs to the server's commands; leave those to vanilla
        if (prefix.isEmpty() || prefix.equals("/") || !value.startsWith(prefix)) return;
        int cursor = Math.min(input.getCursorPosition(), value.length());
        CommandManager.Completion c = Prism.commands().complete(value.substring(0, cursor));
        if (c == null) return;
        ci.cancel();

        currentParse = null;
        currentParseIsCommand = false;
        currentParseIsMessage = false;
        if (!keepSuggestions) {
            input.setSuggestion(null);
            suggestions = null;
        }
        commandUsage.clear();
        if (c.usage() != null) {
            Component line = Component.literal(c.usage()).withStyle(c.error() ? ChatFormatting.RED : ChatFormatting.GRAY);
            if (c.description() != null) line = line.copy().append(Component.literal("  " + c.description()).withStyle(ChatFormatting.DARK_GRAY));
            commandUsage.add(line.getVisualOrderText());
        }
        recomputeUsageBoxWidth();
        commandUsagePosition = 0;

        // while Tab is cycling through a list, keep that list instead of rebuilding it from the half-applied text
        if (suggestions != null && keepSuggestions) return;
        SuggestionsBuilder builder = new SuggestionsBuilder(value.substring(0, cursor), c.start());
        for (String option : c.options()) builder.suggest(option);
        pendingSuggestions = builder.buildFuture();
        suggestions = null;
        if (allowSuggestions && Minecraft.getInstance().options.autoSuggestions().get()) showSuggestions(false);
    }
}
